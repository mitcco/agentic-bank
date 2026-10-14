package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

/**
 * The defaults shipped in application.properties for the outbox publisher and the Kafka producer, read without
 * starting the application (no database, no overrides): the publisher must be off unless someone turns it on.
 */
class NotificationPublisherDefaultPropertiesTest {

  private static void withDefaults(java.util.function.Consumer<Environment> check) {
    new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .run(context -> check.accept(context.getEnvironment()));
  }

  @Test
  void thePublisherIsOffByDefaultAndTheScheduleAndBatchAreTheAgreedOnes() {
    withDefaults(env -> {
      assertThat(env.getProperty("notification.publisher.enabled")).isEqualTo("false");
      assertThat(env.getProperty("notification.publisher.fixed-delay")).isEqualTo("1s");
      assertThat(env.getProperty("notification.publisher.initial-delay")).isEqualTo("5s");
      assertThat(env.getProperty("notification.publisher.batch-size")).isEqualTo("100");
      assertThat(env.getProperty("notification.publisher.send-timeout")).isEqualTo("10s");
    });
  }

  @Test
  void theTopicIsTheOneOfTheContract() {
    withDefaults(env -> assertThat(env.getProperty("notification.topic")).isEqualTo("transfer.notifications"));
  }

  @Test
  void theProducerPointsToTheLocalBrokerAndWaitsForAllTheReplicas() {
    withDefaults(env -> {
      assertThat(env.getProperty("spring.kafka.bootstrap-servers")).isEqualTo("localhost:9092");
      assertThat(env.getProperty("spring.kafka.producer.acks")).isEqualTo("all");
      assertThat(env.getProperty("spring.kafka.producer.key-serializer"))
        .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
      assertThat(env.getProperty("spring.kafka.producer.value-serializer"))
        .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
      assertThat(env.getProperty("spring.kafka.producer.properties.enable.idempotence")).isEqualTo("true");
    });
  }

  @Test
  void theProducerTimeoutsAreCoherentWithEachOtherAndWithTheSendTimeout() {
    withDefaults(env -> {
      long maxBlock = Long.parseLong(env.getRequiredProperty("spring.kafka.producer.properties.max.block.ms"));
      long request = Long.parseLong(env.getRequiredProperty("spring.kafka.producer.properties.request.timeout.ms"));
      long delivery = Long.parseLong(env.getRequiredProperty("spring.kafka.producer.properties.delivery.timeout.ms"));
      long linger = Long.parseLong(env.getRequiredProperty("spring.kafka.producer.properties.linger.ms"));
      assertThat(maxBlock).isEqualTo(2000);
      assertThat(request).isEqualTo(2000);
      assertThat(delivery).isEqualTo(5000);
      assertThat(linger).isZero();
      // the producer refuses to start sending otherwise
      assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
      // the wait for the ack must outlast what the producer itself may take
      Duration sendTimeout = DurationStyle.detectAndParse(env.getRequiredProperty("notification.publisher.send-timeout"));
      assertThat(sendTimeout).isGreaterThan(Duration.ofMillis(maxBlock + delivery));
    });
  }
}
