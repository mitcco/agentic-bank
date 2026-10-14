package com.bank.notification.kafka.config;

import com.bank.notification.kafka.port.in.kafka.LoggableTransferId;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.util.Objects;

/**
 * Called when the attempts are over: writes one ERROR line and lets the offset move on. It logs
 * only the transfer id (when UUID-shaped) and the class of the cause, never a message or a
 * throwable, and it never throws.
 *
 * <p>Known limit: the number of attempts in the text comes from the retry constants of
 * {@link NotificationKafkaListenerFactory}. For exceptions that {@code DefaultErrorHandler}
 * already treats as not retryable (for example {@code ClassCastException}) the recoverer runs at
 * the first attempt, yet the text still says the same number of attempts. This is accepted: the
 * listener already handles the domain and parsing failures that are not retryable.
 */
public final class DiscardingRecordRecoverer implements ConsumerRecordRecoverer {

  /** First delivery plus the retries. */
  private static final long ATTEMPTS = NotificationKafkaListenerFactory.MAX_RETRIES + 1;

  private final Logger logger;

  public DiscardingRecordRecoverer() {
    this(LoggerFactory.getLogger(DiscardingRecordRecoverer.class));
  }

  DiscardingRecordRecoverer(Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Exception exception) {
    Object key = record.key();
    String keyText = key instanceof String text ? text : null;
    Throwable cause = exception instanceof ListenerExecutionFailedException
        && exception.getCause() != null ? exception.getCause() : exception;
    logger.error("Transfer notification discarded after " + ATTEMPTS
            + " attempts transferId={} cause={}",
        LoggableTransferId.of(null, keyText), cause.getClass().getName());
  }
}
