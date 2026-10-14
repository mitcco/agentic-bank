package com.bank.notification.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.util.Map;

/**
 * Tests of the container factory and of the error handler. The consumer factory is a real
 * {@code DefaultKafkaConsumerFactory}: it connects only when a consumer is created, which these
 * tests never do. Retries and acknowledgement against a broker are proved by the integration
 * test.
 */
class NotificationKafkaListenerFactoryTest {

  private static ConsumerFactory<String, String> consumerFactory() {
    return new DefaultKafkaConsumerFactory<>(Map.<String, Object>of(
        "bootstrap.servers", "localhost:1",
        "key.deserializer", StringDeserializer.class,
        "value.deserializer", StringDeserializer.class));
  }

  @Test
  void buildsAContainerFactoryWithTheGivenConsumerFactoryAndRecordAcknowledgement() {
    ConsumerFactory<String, String> consumerFactory = consumerFactory();

    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        NotificationKafkaListenerFactory.containerFactory(consumerFactory);

    assertThat(factory.getConsumerFactory()).isSameAs(consumerFactory);
    assertThat(factory.getContainerProperties().getAckMode())
        .isEqualTo(ContainerProperties.AckMode.RECORD);
  }

  @Test
  void retriesTwiceMoreEveryOneSecond() {
    assertThat(NotificationKafkaListenerFactory.RETRY_INTERVAL_MS).isEqualTo(1000L);
    assertThat(NotificationKafkaListenerFactory.MAX_RETRIES).isEqualTo(2L);
  }

  @Test
  void buildsAnErrorHandlerOfTypeDefaultErrorHandler() {
    assertThat(NotificationKafkaListenerFactory.errorHandler())
        .isInstanceOf(DefaultErrorHandler.class);
  }

  @Test
  void buildsANewErrorHandlerOnEveryCall() {
    assertThat(NotificationKafkaListenerFactory.errorHandler())
        .isNotSameAs(NotificationKafkaListenerFactory.errorHandler());
  }

  @Test
  void rejectsANullConsumerFactory() {
    assertThatNullPointerException()
        .isThrownBy(() -> NotificationKafkaListenerFactory.containerFactory(null));
  }
}
