package com.bank.notification.kafka.port.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.bank.notification.kafka.config.NotificationKafkaListenerFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * The only integration test of the Kafka adapter, against the broker of the Docker compose
 * (address from {@code KAFKA_BOOTSTRAP_SERVERS}, default {@code localhost:9092}). It is skipped,
 * visibly, when the broker does not answer within a few seconds. Topic and consumer group have a
 * unique name for each run and are deleted at the end; the topic and the group of development
 * are never used.
 */
class TransferNotificationKafkaListenerIntegrationTest {

  private static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";
  private static final String SKIP_MESSAGE = "Kafka not reachable: integration test skipped";
  private static final String TEST_PREFIX = "notification-it-";
  private static final int PING_TIMEOUT_MS = 3000;

  private static final String TRANSFER_ID_A = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String TRANSFER_ID_B = "9d1c7e52-4b3a-4f08-8c6d-2a5e1b7f9c04";
  private static final List<String> IBAN_FRAGMENTS = List.of(
      "IT60X0542811101000000000001", "IT60X0542811101000000000002",
      "IT60X0542811101000000000099", "0542811101");

  private static String bootstrapServers;
  private static boolean reachable;

  private String topic;
  private String group;
  private AdminClient admin;
  private KafkaProducer<String, String> producer;
  private AnnotationConfigApplicationContext context;
  private CollectingAppender appender;
  private ch.qos.logback.classic.Logger rootLogger;
  private final CountDownLatch release = new CountDownLatch(1);

  /** Enables the listener annotations; the beans are registered by the test. */
  @Configuration
  @EnableKafka
  static class KafkaEnabler {
  }

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

  @BeforeAll
  static void checkBroker() {
    String configured = System.getenv("KAFKA_BOOTSTRAP_SERVERS");
    bootstrapServers =
        configured == null || configured.isBlank() ? DEFAULT_BOOTSTRAP_SERVERS : configured;
    try (AdminClient ping = AdminClient.create(adminProperties(PING_TIMEOUT_MS))) {
      ping.describeCluster().nodes().get(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      reachable = true;
    } catch (TimeoutException e) {
      // Only a timeout means "broker absent": the test is skipped. Any other error fails it.
      reachable = false;
    } catch (ExecutionException e) {
      if (e.getCause() instanceof org.apache.kafka.common.errors.TimeoutException) {
        reachable = false;
      } else {
        throw new IllegalStateException("Kafka ping failed", e);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Kafka ping interrupted", e);
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(reachable, SKIP_MESSAGE);
    topic = TEST_PREFIX + UUID.randomUUID();
    group = TEST_PREFIX + UUID.randomUUID();
    requireTestName(topic);
    requireTestName(group);
    admin = AdminClient.create(adminProperties(15000));
    admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
    producer = new KafkaProducer<>(Map.<String, Object>of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.ACKS_CONFIG, "all",
        ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000,
        ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000,
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000));
    appender = new CollectingAppender();
    appender.start();
    rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
        org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(appender);
  }

  @AfterEach
  void tearDown() throws Exception {
    release.countDown();
    if (context != null) {
      context.close();
    }
    if (producer != null) {
      producer.close(Duration.ofSeconds(5));
    }
    if (rootLogger != null && appender != null) {
      rootLogger.detachAppender(appender);
      appender.stop();
    }
    if (admin != null) {
      deleteTopicAndGroup();
      admin.close(Duration.ofSeconds(5));
    }
  }

  @Test
  void retriesThreeTimesThenDiscardsAndCommitsTheOffsetOnlyAfterTheListenerReturns()
      throws Exception {
    List<Long> attemptsA = new CopyOnWriteArrayList<>();
    List<String> receivedB = new CopyOnWriteArrayList<>();
    FakeNotifyTransferInPort port = new FakeNotifyTransferInPort().onCall(command -> {
      if (TRANSFER_ID_A.equals(command.transferId())) {
        attemptsA.add(System.nanoTime());
        throw new IllegalStateException("simulated database failure");
      }
      receivedB.add(command.transferId());
      awaitRelease();
    });
    startContext(port);

    send(TRANSFER_ID_A);
    send(TRANSFER_ID_B);

    // M1: the offset must stay unconfirmed while the attempts run.
    await(() -> !attemptsA.isEmpty(), 30, "first attempt of the first message");
    assertThat(committedOffset()).as("offset while M1 is being retried").isEqualTo(-1L);

    // M1 gets exactly 3 attempts, at least 900 ms apart; then M2 reaches the use case.
    await(() -> !receivedB.isEmpty(), 30, "second message in the use case");
    assertThat(attemptsA).hasSize(3);
    assertThat(TimeUnit.NANOSECONDS.toMillis(attemptsA.get(1) - attemptsA.get(0)))
        .isGreaterThanOrEqualTo(900L);
    assertThat(TimeUnit.NANOSECONDS.toMillis(attemptsA.get(2) - attemptsA.get(1)))
        .isGreaterThanOrEqualTo(900L);

    // M2 is blocked in the use case: nothing has been confirmed for it.
    assertThat(committedOffset()).as("offset while M2 is in the use case").isNotEqualTo(2L);

    // M2 returns: its offset is confirmed.
    release.countDown();
    await(() -> committedOffset() == 2L, 15, "committed offset 2");
    assertThat(committedOffset()).isEqualTo(2L);
    assertThat(receivedB).containsExactly(TRANSFER_ID_B);
    assertThat(attemptsA).hasSize(3);

    // Logs: one final ERROR for M1 and no IBAN anywhere (scenario 10).
    List<ILoggingEvent> discarded = appender.events().stream()
        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
        .filter(event -> event.getFormattedMessage().contains("discarded after 3 attempts"))
        .toList();
    assertThat(discarded).hasSize(1);
    assertThat(discarded.get(0).getFormattedMessage()).contains(TRANSFER_ID_A);
    for (ILoggingEvent event : appender.events()) {
      assertNoIban(event.getFormattedMessage());
      IThrowableProxy throwable = event.getThrowableProxy();
      while (throwable != null) {
        assertNoIban(throwable.getClassName() + ": " + throwable.getMessage());
        throwable = throwable.getCause();
      }
    }
  }

  private void startContext(FakeNotifyTransferInPort port) {
    ConcurrentKafkaListenerContainerFactory<String, String> containerFactory =
        NotificationKafkaListenerFactory.containerFactory(new DefaultKafkaConsumerFactory<>(
            Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)));
    context = new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources().addFirst(
        new MapPropertySource("integration-test", Map.<String, Object>of(
            "notification.topic", topic)));
    context.register(KafkaEnabler.class);
    context.registerBean("kafkaListenerContainerFactory",
        ConcurrentKafkaListenerContainerFactory.class, () -> containerFactory);
    context.registerBean("transferNotificationKafkaListener",
        TransferNotificationKafkaListener.class,
        () -> new TransferNotificationKafkaListener(port));
    context.refresh();
  }

  private void awaitRelease() {
    try {
      if (!release.await(60, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch not released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the latch", e);
    }
  }

  private void send(String transferId) throws Exception {
    String value = "{\"transferId\":\"" + transferId + "\","
        + "\"sourceIban\":\"IT60X0542811101000000000001\","
        + "\"destinationIban\":\"IT60X0542811101000000000002\","
        + "\"amount\":100.25,\"currency\":\"EUR\",\"status\":\"COMPLETED\","
        + "\"occurredAt\":\"2026-10-13T09:30:00Z\"}";
    producer.send(new ProducerRecord<>(topic, transferId, value)).get(15, TimeUnit.SECONDS);
  }

  /** Confirmed offset of the test group on partition 0, or -1 when there is none. */
  private long committedOffset() {
    Map<TopicPartition, OffsetAndMetadata> offsets;
    try {
      offsets = admin.listConsumerGroupOffsets(group)
          .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Could not read the committed offsets", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reading the committed offsets", e);
    }
    OffsetAndMetadata offset = offsets.get(new TopicPartition(topic, 0));
    return offset == null ? -1L : offset.offset();
  }

  private static void await(BooleanSupplier condition, int seconds, String what) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(50);
    }
    assertThat(condition.getAsBoolean()).as("timed out waiting for " + what).isTrue();
  }

  private static void assertNoIban(String text) {
    for (String fragment : IBAN_FRAGMENTS) {
      assertThat(text).doesNotContain(fragment);
    }
  }

  private void deleteTopicAndGroup() throws Exception {
    requireTestName(topic);
    requireTestName(group);
    try {
      admin.deleteTopics(List.of(topic)).all().get(15, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException e) {
      System.err.println("Could not delete the test topic " + topic + ": " + e);
    }
    for (int attempt = 0; attempt < 10; attempt++) {
      try {
        admin.deleteConsumerGroups(List.of(group)).all().get(15, TimeUnit.SECONDS);
        return;
      } catch (ExecutionException e) {
        if (e.getCause() instanceof GroupNotEmptyException) {
          Thread.sleep(500);
        } else if (e.getCause() instanceof GroupIdNotFoundException) {
          // The group does not exist when nothing was ever committed: nothing to delete.
          return;
        } else {
          // Any other failure (authorization, broker error) is reported, but cleanup must
          // never fail the test: only the class name is printed, never the message.
          Throwable cause = e.getCause() == null ? e : e.getCause();
          System.err.println("Could not delete the test group " + group + ": "
              + cause.getClass().getName());
          return;
        }
      } catch (TimeoutException e) {
        System.err.println("Could not delete the test group " + group + ": " + e);
        return;
      }
    }
    System.err.println("The test group " + group + " is still not empty and was not deleted");
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
