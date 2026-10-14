package com.bank.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * The only end-to-end test of the service: a record published on a Kafka topic reaches the real
 * listener, the service, MongoDB and the log. It runs against the Kafka and the MongoDB of the
 * Docker compose and is skipped, visibly, when one of them does not answer. Topic and consumer
 * group are unique for each run (prefix {@code notification-it-}) and the database is always
 * {@code notification_test}: the topic, the group and the database of development are never used.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationEndToEndIntegrationTest {

  private static final String SKIP_MESSAGE =
      "Kafka or MongoDB not reachable: end-to-end test skipped";
  private static final String TEST_PREFIX = "notification-it-";
  private static final String TEST_DATABASE = "notification_test";
  private static final String COLLECTION = "transfer_notifications";
  private static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";
  private static final String DEFAULT_MONGO_URI =
      "mongodb://notification:notification_dev@localhost:27018/notification?authSource=admin";
  private static final int KAFKA_PING_TIMEOUT_MS = 3000;

  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";
  private static final List<String> IBAN_FRAGMENTS =
      List.of(SOURCE, DESTINATION, "IT60X0542811101000000000099", "0542811101");

  private static final String TOPIC = TEST_PREFIX + UUID.randomUUID();
  private static final String GROUP = TEST_PREFIX + UUID.randomUUID();

  private static String bootstrapServers;
  private static AdminClient admin;

  /** Thread-safe collector of Logback events. */
  static class CollectingAppender extends AppenderBase<ILoggingEvent> {

    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    @Override
    protected void append(ILoggingEvent event) {
      events.add(event);
    }

    List<ILoggingEvent> events() {
      return events;
    }
  }

  @Autowired
  private KafkaListenerEndpointRegistry registry;

  @Autowired
  private MongoTemplate mongoTemplate;

  private String transferId;
  private CollectingAppender appender;
  private ch.qos.logback.classic.Logger rootLogger;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("notification.topic", () -> TOPIC);
    registry.add("spring.kafka.consumer.group-id", () -> GROUP);
    registry.add("notification.listener.auto-startup", () -> "true");
    registry.add("spring.mongodb.database", () -> TEST_DATABASE);
  }

  @BeforeAll
  static void checkServicesAndCreateTopic() throws Exception {
    requireTestName(TOPIC);
    requireTestName(GROUP);
    String configured = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
    bootstrapServers =
        configured == null || configured.isBlank() ? DEFAULT_BOOTSTRAP_SERVERS : configured;
    boolean kafkaUp = pingKafka();
    boolean mongoUp = pingMongo();
    assumeTrue(kafkaUp && mongoUp, SKIP_MESSAGE);
    admin = AdminClient.create(adminProperties(15000));
    admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
  }

  @AfterAll
  static void closeAdmin() {
    if (admin != null) {
      admin.close(Duration.ofSeconds(5));
    }
  }

  @BeforeEach
  void setUp() {
    appender = new CollectingAppender();
    appender.start();
    rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
        org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() {
    if (rootLogger != null && appender != null) {
      rootLogger.detachAppender(appender);
      appender.stop();
    }
    // Cleanup must never fail the test: errors are reported with the class name only.
    try {
      registry.stop();
    } catch (RuntimeException e) {
      reportCleanupFailure("stop the listener", e);
    }
    if (transferId != null) {
      try {
        mongoTemplate.getCollection(COLLECTION).deleteOne(new Document("_id", transferId));
      } catch (RuntimeException e) {
        reportCleanupFailure("delete the test document", e);
      }
    }
    if (admin != null) {
      deleteTopicAndGroup();
    }
  }

  @Test
  void aCompletedNotificationPublishedOnKafkaIsStoredInMongoAndLoggedOnceWithoutIban()
      throws Exception {
    assertThat(mongoTemplate.getDb().getName()).isEqualTo(TEST_DATABASE);
    requireTestName(TOPIC);
    requireTestName(GROUP);
    transferId = UUID.randomUUID().toString();

    publish(transferId, completedJson(transferId));

    await(() -> findDocument() != null, 30, "the stored document");
    Document stored = findDocument();
    assertThat(stored).isNotNull();
    assertThat(stored.get("_id")).isInstanceOf(String.class).isEqualTo(transferId);
    assertThat(stored.get("amount")).isInstanceOf(Decimal128.class);
    BigDecimal amount = ((Decimal128) stored.get("amount")).bigDecimalValue();
    assertThat(amount).isEqualByComparingTo("100.25");
    assertThat(amount.scale()).isEqualTo(2);
    assertThat(stored.get("occurredAt")).isInstanceOf(Date.class)
        .isEqualTo(Date.from(Instant.parse("2026-10-13T09:30:00Z")));
    assertThat(stored.get("status")).isInstanceOf(String.class).isEqualTo("COMPLETED");
    assertThat(stored.get("reasons")).isInstanceOf(List.class);
    assertThat(stored.getList("reasons", String.class)).isEmpty();
    assertThat(stored.getString("sourceIban")).isEqualTo(SOURCE);
    assertThat(stored.getString("destinationIban")).isEqualTo(DESTINATION);
    assertThat(stored.getString("currency")).isEqualTo("EUR");
    assertThat(stored.keySet().stream().filter(key -> !key.equals("_class")).toList())
        .containsExactlyInAnyOrder("_id", "sourceIban", "destinationIban", "amount", "currency",
            "status", "reasons", "occurredAt");

    await(() -> infoLinesFor(transferId).size() >= 1, 30, "the log line");
    assertThat(infoLinesFor(transferId)).hasSize(1);
    for (ILoggingEvent event : appender.events()) {
      assertNoIban(event.getFormattedMessage());
      IThrowableProxy throwable = event.getThrowableProxy();
      while (throwable != null) {
        assertNoIban(throwable.getClassName() + ": " + throwable.getMessage());
        throwable = throwable.getCause();
      }
    }

    await(() -> committedOffset() == 1L, 30, "committed offset 1");
    assertThat(committedOffset()).isEqualTo(1L);
  }

  private Document findDocument() {
    return mongoTemplate.getCollection(COLLECTION).find(new Document("_id", transferId)).first();
  }

  private List<ILoggingEvent> infoLinesFor(String id) {
    return appender.events().stream()
        .filter(event -> event.getLevel() == Level.INFO)
        .filter(event -> event.getFormattedMessage().contains(id))
        .toList();
  }

  private static String completedJson(String id) {
    return "{\"transferId\":\"" + id + "\","
        + "\"sourceIban\":\"" + SOURCE + "\","
        + "\"destinationIban\":\"" + DESTINATION + "\","
        + "\"amount\":100.25,\"currency\":\"EUR\",\"status\":\"COMPLETED\","
        + "\"occurredAt\":\"2026-10-13T09:30:00Z\"}";
  }

  private static void publish(String key, String json) throws Exception {
    requireTestName(TOPIC);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.<String, Object>of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.ACKS_CONFIG, "all",
        ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000))) {
      producer.send(new ProducerRecord<>(TOPIC, key, json)).get(15, TimeUnit.SECONDS);
    }
  }

  /** Confirmed offset of the test group on partition 0, or -1 when there is none. */
  private static long committedOffset() {
    Map<TopicPartition, OffsetAndMetadata> offsets;
    try {
      offsets = admin.listConsumerGroupOffsets(GROUP)
          .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Could not read the committed offsets", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading the committed offsets", e);
    }
    OffsetAndMetadata offset = offsets.get(new TopicPartition(TOPIC, 0));
    return offset == null ? -1L : offset.offset();
  }

  private static void await(BooleanSupplier condition, int seconds, String what) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(100);
    }
    assertThat(condition.getAsBoolean()).as("timed out waiting for " + what).isTrue();
  }

  private static void assertNoIban(String text) {
    for (String fragment : IBAN_FRAGMENTS) {
      assertThat(text).doesNotContain(fragment);
    }
  }

  /** Only a timeout means "Kafka absent"; any other error fails the test. */
  private static boolean pingKafka() {
    try (AdminClient ping = AdminClient.create(adminProperties(KAFKA_PING_TIMEOUT_MS))) {
      ping.describeCluster().nodes().get(KAFKA_PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException e) {
      return false;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof org.apache.kafka.common.errors.TimeoutException) {
        return false;
      }
      throw new IllegalStateException("Kafka ping failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Kafka ping interrupted", e);
    }
  }

  /** Only the server selection timeout means "Mongo absent"; any other error fails the test. */
  private static boolean pingMongo() {
    String uri = System.getenv("MONGODB_URI");
    if (uri == null || uri.isBlank()) {
      uri = DEFAULT_MONGO_URI;
    }
    MongoClientSettings settings = MongoClientSettings.builder()
        .applyConnectionString(new ConnectionString(uri))
        .applyToClusterSettings(builder -> builder.serverSelectionTimeout(2, TimeUnit.SECONDS))
        .build();
    try (MongoClient client = MongoClients.create(settings)) {
      client.getDatabase("admin").runCommand(new Document("ping", 1));
      return true;
    } catch (MongoTimeoutException e) {
      return false;
    }
  }

  private static void deleteTopicAndGroup() {
    try {
      requireTestName(TOPIC);
      requireTestName(GROUP);
    } catch (IllegalStateException e) {
      reportCleanupFailure("validate the test names", e);
      return;
    }
    try {
      admin.deleteTopics(List.of(TOPIC)).all().get(15, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      reportCleanupFailure("delete the test topic", e.getCause() == null ? e : e.getCause());
    } catch (TimeoutException | RuntimeException e) {
      reportCleanupFailure("delete the test topic", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return;
    }
    for (int attempt = 0; attempt < 10; attempt++) {
      try {
        admin.deleteConsumerGroups(List.of(GROUP)).all().get(15, TimeUnit.SECONDS);
        return;
      } catch (ExecutionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof GroupNotEmptyException) {
          if (!sleepQuietly(500)) {
            return;
          }
        } else if (cause instanceof GroupIdNotFoundException) {
          return;
        } else {
          reportCleanupFailure("delete the test group", cause);
          return;
        }
      } catch (TimeoutException | RuntimeException e) {
        reportCleanupFailure("delete the test group", e);
        return;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    System.err.println("The test group is still not empty and was not deleted");
  }

  private static boolean sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Prints only the class name of the failure, never its message. */
  private static void reportCleanupFailure(String action, Throwable failure) {
    System.err.println("Could not " + action + ": " + failure.getClass().getName());
  }

  /** Guard against touching the topic or the group of development. */
  private static void requireTestName(String name) {
    if (name == null || !name.startsWith(TEST_PREFIX)) {
      throw new IllegalStateException("Integration tests must only use names starting with "
          + TEST_PREFIX);
    }
  }

  private static Map<String, Object> adminProperties(int timeoutMs) {
    return Map.of(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs,
        AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs,
        AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG, 2000,
        AdminClientConfig.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG, 2000);
  }
}
