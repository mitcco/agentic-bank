package com.bank.transfer.kafka.port.out.kafka.impl;

import com.bank.transfer.application.exception.NotificationSendException;
import com.bank.transfer.application.port.out.NotificationSenderOutPort;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.kafka.core.KafkaOperations;

/**
 * Sends one notification and waits for the broker ack. Every failure becomes {@link NotificationSendException}.
 * Logs only the transfer id, the category and the simple class name of the cause: never payloads, IBANs, amounts or
 * the message of the cause.
 */
public class KafkaNotificationSenderOutPortImpl implements NotificationSenderOutPort {

  private static final Log LOG = LogFactory.getLog(KafkaNotificationSenderOutPortImpl.class);

  private final KafkaOperations<String, String> kafka;
  private final String topic;
  private final Duration sendTimeout;

  public KafkaNotificationSenderOutPortImpl(KafkaOperations<String, String> kafka, String topic,
    Duration sendTimeout) {
    this.kafka = Objects.requireNonNull(kafka, "kafka");
    if (topic == null || topic.isBlank()) {
      throw new IllegalArgumentException("topic must not be empty");
    }
    if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
      throw new IllegalArgumentException("sendTimeout must be positive");
    }
    this.topic = topic;
    this.sendTimeout = sendTimeout;
  }

  @Override
  public void send(String transferId, String payload) {
    try {
      kafka.send(topic, transferId, payload).get(sendTimeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw failed(transferId, "INTERRUPTED", e);
    } catch (ExecutionException e) {
      throw failed(transferId, "NOT_ACKNOWLEDGED", e.getCause() != null ? e.getCause() : e);
    } catch (TimeoutException e) {
      throw failed(transferId, "ACK_TIMEOUT", null);
    } catch (RuntimeException e) {
      throw failed(transferId, "PRODUCER_ERROR", e);
    }
  }

  private NotificationSendException failed(String transferId, String category, Throwable cause) {
    LOG.warn("Notification not sent for transfer " + transferId + ": " + category
      + (cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")"));
    return new NotificationSendException(transferId);
  }
}
