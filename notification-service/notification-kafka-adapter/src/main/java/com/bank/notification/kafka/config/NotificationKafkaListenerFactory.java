package com.bank.notification.kafka.config;

import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Objects;

/**
 * Builds the container factory and the error handler of the notification listener. It has no
 * Spring annotations: the bootstrap calls it when it wires the beans.
 */
public final class NotificationKafkaListenerFactory {

  /** Pause between two attempts, in milliseconds. */
  public static final long RETRY_INTERVAL_MS = 1000L;

  /** Retries after the first delivery: three calls in total. */
  public static final long MAX_RETRIES = 2L;

  private NotificationKafkaListenerFactory() {
  }

  /**
   * Builds the error handler: fixed back-off, then the record is discarded with an ERROR line.
   *
   * @return a new handler
   */
  public static DefaultErrorHandler errorHandler() {
    DefaultErrorHandler handler = new DefaultErrorHandler(
        new DiscardingRecordRecoverer(), new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES));
    handler.setLogLevel(KafkaException.Level.DEBUG);
    return handler;
  }

  /**
   * Builds the container factory: one thread, offset acknowledged after each record.
   *
   * @param consumerFactory the consumer factory
   * @return the container factory
   */
  public static ConcurrentKafkaListenerContainerFactory<String, String> containerFactory(
      ConsumerFactory<String, String> consumerFactory) {
    Objects.requireNonNull(consumerFactory, "consumerFactory");
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory);
    factory.setCommonErrorHandler(errorHandler());
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    factory.setConcurrency(1);
    return factory;
  }
}
