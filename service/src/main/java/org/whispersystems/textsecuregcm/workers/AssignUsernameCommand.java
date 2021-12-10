/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.workers;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.dropwizard.Application;
import io.dropwizard.cli.EnvironmentCommand;
import io.dropwizard.setup.Environment;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.Metrics;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.WhisperServerConfiguration;
import org.whispersystems.textsecuregcm.auth.ExternalServiceCredentialGenerator;
import org.whispersystems.textsecuregcm.configuration.dynamic.DynamicConfiguration;
import org.whispersystems.textsecuregcm.metrics.PushLatencyManager;
import org.whispersystems.textsecuregcm.push.ClientPresenceManager;
import org.whispersystems.textsecuregcm.redis.FaultTolerantRedisCluster;
import org.whispersystems.textsecuregcm.securebackup.SecureBackupClient;
import org.whispersystems.textsecuregcm.securestorage.SecureStorageClient;
import org.whispersystems.textsecuregcm.sqs.DirectoryQueue;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.Accounts;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.AccountsManager.DeletionReason;
import org.whispersystems.textsecuregcm.storage.DeletedAccounts;
import org.whispersystems.textsecuregcm.storage.DeletedAccountsManager;
import org.whispersystems.textsecuregcm.storage.DynamicConfigurationManager;
import org.whispersystems.textsecuregcm.storage.Keys;
import org.whispersystems.textsecuregcm.storage.MessagesCache;
import org.whispersystems.textsecuregcm.storage.MessagesDynamoDb;
import org.whispersystems.textsecuregcm.storage.MessagesManager;
import org.whispersystems.textsecuregcm.storage.PhoneNumberIdentifiers;
import org.whispersystems.textsecuregcm.storage.Profiles;
import org.whispersystems.textsecuregcm.storage.ProfilesManager;
import org.whispersystems.textsecuregcm.storage.ReportMessageDynamoDb;
import org.whispersystems.textsecuregcm.storage.ReportMessageManager;
import org.whispersystems.textsecuregcm.storage.ReservedUsernames;
import org.whispersystems.textsecuregcm.storage.StoredVerificationCodeManager;
import org.whispersystems.textsecuregcm.storage.UsernameNotAvailableException;
import org.whispersystems.textsecuregcm.storage.VerificationCodeStore;
import org.whispersystems.textsecuregcm.util.DynamoDbFromConfig;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.codahale.metrics.MetricRegistry.name;

public class AssignUsernameCommand extends EnvironmentCommand<WhisperServerConfiguration> {

  public AssignUsernameCommand() {
    super(new Application<>() {
      @Override
      public void run(WhisperServerConfiguration configuration, Environment environment) {

      }
    }, "assign-username", "assign a username to an account");
  }

  @Override
  public void configure(Subparser subparser) {
    super.configure(subparser);

    subparser.addArgument("-n", "--username")
        .dest("username")
        .type(String.class)
        .required(true)
        .help("The username to assign");

    subparser.addArgument("-a", "--aci")
        .dest("aci")
        .type(String.class)
        .required(true)
        .help("The ACI of the account to which to assign the username");
  }

  @Override
  protected void run(Environment environment, Namespace namespace,
      WhisperServerConfiguration configuration)
      throws Exception {
    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    ClientResources redisClusterClientResources = ClientResources.builder().build();

    DynamoDbClient reportMessagesDynamoDb = DynamoDbFromConfig.client(
        configuration.getReportMessageDynamoDbConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient messageDynamoDb = DynamoDbFromConfig.client(configuration.getMessageDynamoDbConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient preKeysDynamoDb = DynamoDbFromConfig.client(configuration.getKeysDynamoDbConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient accountsDynamoDbClient = DynamoDbFromConfig.client(
        configuration.getAccountsDynamoDbConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient deletedAccountsDynamoDbClient = DynamoDbFromConfig.client(
        configuration.getDeletedAccountsDynamoDbConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());
    DynamoDbClient phoneNumberIdentifiersDynamoDbClient =
        DynamoDbFromConfig.client(configuration.getPhoneNumberIdentifiersDynamoDbConfiguration(),
            software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());

    FaultTolerantRedisCluster cacheCluster = new FaultTolerantRedisCluster("main_cache_cluster",
        configuration.getCacheClusterConfiguration(), redisClusterClientResources);

    ExecutorService keyspaceNotificationDispatchExecutor = environment.lifecycle()
        .executorService(name(getClass(), "keyspaceNotification-%d")).maxThreads(4).build();
    ExecutorService backupServiceExecutor = environment.lifecycle()
        .executorService(name(getClass(), "backupService-%d")).maxThreads(8).minThreads(1).build();
    ExecutorService storageServiceExecutor = environment.lifecycle()
        .executorService(name(getClass(), "storageService-%d")).maxThreads(8).minThreads(1).build();

    ExternalServiceCredentialGenerator backupCredentialsGenerator = new ExternalServiceCredentialGenerator(
        configuration.getSecureBackupServiceConfiguration().getUserAuthenticationTokenSharedSecret(), true);
    ExternalServiceCredentialGenerator storageCredentialsGenerator = new ExternalServiceCredentialGenerator(
        configuration.getSecureStorageServiceConfiguration().getUserAuthenticationTokenSharedSecret(), true);

    DynamicConfigurationManager<DynamicConfiguration> dynamicConfigurationManager = new DynamicConfigurationManager<>(
        configuration.getAppConfig().getApplication(), configuration.getAppConfig().getEnvironment(),
        configuration.getAppConfig().getConfigurationName(), DynamicConfiguration.class);
    dynamicConfigurationManager.start();

    DynamoDbClient pendingAccountsDynamoDbClient = DynamoDbFromConfig.client(
        configuration.getPendingAccountsDynamoDbConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());

    DynamoDbClient reservedUsernamesDynamoDbClient =
        DynamoDbFromConfig.client(configuration.getReservedUsernamesDynamoDbConfiguration(),
            software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());

    DynamoDbAsyncClient dynamoDbAsyncClient = DynamoDbFromConfig.asyncClient(
        configuration.getDynamoDbClientConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());

    DynamoDbClient dynamoDbClient = DynamoDbFromConfig.client(
        configuration.getDynamoDbClientConfiguration(),
        software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider.create());

    AmazonDynamoDB deletedAccountsLockDynamoDbClient = AmazonDynamoDBClientBuilder.standard()
        .withRegion(configuration.getDeletedAccountsLockDynamoDbConfiguration().getRegion())
        .withClientConfiguration(new ClientConfiguration().withClientExecutionTimeout(
                ((int) configuration.getDeletedAccountsLockDynamoDbConfiguration().getClientExecutionTimeout()
                    .toMillis()))
            .withRequestTimeout(
                (int) configuration.getDeletedAccountsLockDynamoDbConfiguration().getClientRequestTimeout()
                    .toMillis()))
        .withCredentials(InstanceProfileCredentialsProvider.getInstance())
        .build();

    DeletedAccounts deletedAccounts = new DeletedAccounts(deletedAccountsDynamoDbClient,
        configuration.getDeletedAccountsDynamoDbConfiguration().getTableName(),
        configuration.getDeletedAccountsDynamoDbConfiguration().getNeedsReconciliationIndexName());
    VerificationCodeStore pendingAccounts = new VerificationCodeStore(pendingAccountsDynamoDbClient,
        configuration.getPendingAccountsDynamoDbConfiguration().getTableName());

    Accounts accounts = new Accounts(accountsDynamoDbClient,
        configuration.getAccountsDynamoDbConfiguration().getTableName(),
        configuration.getAccountsDynamoDbConfiguration().getPhoneNumberTableName(),
        configuration.getAccountsDynamoDbConfiguration().getPhoneNumberIdentifierTableName(),
        configuration.getAccountsDynamoDbConfiguration().getUsernamesTableName(),
        configuration.getAccountsDynamoDbConfiguration().getScanPageSize());
    PhoneNumberIdentifiers phoneNumberIdentifiers = new PhoneNumberIdentifiers(phoneNumberIdentifiersDynamoDbClient,
        configuration.getPhoneNumberIdentifiersDynamoDbConfiguration().getTableName());
    Profiles profiles = new Profiles(dynamoDbClient, dynamoDbAsyncClient,
        configuration.getDynamoDbTables().getProfiles().getTableName());
    ReservedUsernames reservedUsernames = new ReservedUsernames(reservedUsernamesDynamoDbClient,
        configuration.getReservedUsernamesDynamoDbConfiguration().getTableName());
    Keys keys = new Keys(preKeysDynamoDb,
        configuration.getKeysDynamoDbConfiguration().getTableName());
    MessagesDynamoDb messagesDynamoDb = new MessagesDynamoDb(messageDynamoDb,
        configuration.getMessageDynamoDbConfiguration().getTableName(),
        configuration.getMessageDynamoDbConfiguration().getTimeToLive());
    FaultTolerantRedisCluster messageInsertCacheCluster = new FaultTolerantRedisCluster("message_insert_cluster",
        configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster messageReadDeleteCluster = new FaultTolerantRedisCluster("message_read_delete_cluster",
        configuration.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster metricsCluster = new FaultTolerantRedisCluster("metrics_cluster",
        configuration.getMetricsClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster clientPresenceCluster = new FaultTolerantRedisCluster("client_presence_cluster",
        configuration.getClientPresenceClusterConfiguration(), redisClusterClientResources);
    FaultTolerantRedisCluster rateLimitersCluster = new FaultTolerantRedisCluster("rate_limiters",
        configuration.getRateLimitersCluster(), redisClusterClientResources);
    SecureBackupClient secureBackupClient = new SecureBackupClient(backupCredentialsGenerator, backupServiceExecutor,
        configuration.getSecureBackupServiceConfiguration());
    SecureStorageClient secureStorageClient = new SecureStorageClient(storageCredentialsGenerator,
        storageServiceExecutor, configuration.getSecureStorageServiceConfiguration());
    ClientPresenceManager clientPresenceManager = new ClientPresenceManager(clientPresenceCluster,
        Executors.newSingleThreadScheduledExecutor(), keyspaceNotificationDispatchExecutor);
    MessagesCache messagesCache = new MessagesCache(messageInsertCacheCluster, messageReadDeleteCluster,
        keyspaceNotificationDispatchExecutor);
    PushLatencyManager pushLatencyManager = new PushLatencyManager(metricsCluster, dynamicConfigurationManager);
    DirectoryQueue directoryQueue = new DirectoryQueue(
        configuration.getDirectoryConfiguration().getSqsConfiguration());
    ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
    ReportMessageDynamoDb reportMessageDynamoDb = new ReportMessageDynamoDb(reportMessagesDynamoDb,
        configuration.getReportMessageDynamoDbConfiguration().getTableName(),
        configuration.getReportMessageConfiguration().getReportTtl());
    ReportMessageManager reportMessageManager = new ReportMessageManager(reportMessageDynamoDb, rateLimitersCluster,
        Metrics.globalRegistry, configuration.getReportMessageConfiguration().getCounterTtl());
    MessagesManager messagesManager = new MessagesManager(messagesDynamoDb, messagesCache, pushLatencyManager,
        reportMessageManager);
    DeletedAccountsManager deletedAccountsManager = new DeletedAccountsManager(deletedAccounts,
        deletedAccountsLockDynamoDbClient,
        configuration.getDeletedAccountsLockDynamoDbConfiguration().getTableName());
    StoredVerificationCodeManager pendingAccountsManager = new StoredVerificationCodeManager(pendingAccounts);
    AccountsManager accountsManager = new AccountsManager(accounts, phoneNumberIdentifiers, cacheCluster,
        deletedAccountsManager, directoryQueue, keys, messagesManager, reservedUsernames, profilesManager,
        pendingAccountsManager, secureStorageClient, secureBackupClient, clientPresenceManager, Clock.systemUTC());

    final String username = namespace.getString("username");
    final UUID accountIdentifier = UUID.fromString(namespace.getString("aci"));

    accountsManager.getByAccountIdentifier(accountIdentifier).ifPresentOrElse(account -> {
          try {
            accountsManager.setUsername(account, username);
          } catch (final UsernameNotAvailableException e) {
            throw new IllegalArgumentException("Username already taken");
          }
        },
        () -> {
          throw new IllegalArgumentException("Account not found");
        });
  }
}