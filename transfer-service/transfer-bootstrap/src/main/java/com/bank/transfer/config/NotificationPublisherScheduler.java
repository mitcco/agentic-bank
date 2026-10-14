package com.bank.transfer.config;

import com.bank.transfer.application.dto.PublishResultDto;
import com.bank.transfer.application.exception.InvalidOutboxRowException;
import com.bank.transfer.application.port.in.PublishPendingNotificationsInPort;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodic round of the outbox publisher. Never throws: the next round is the retry. Logs only transfer id, stage
 * and the simple class name of an exception, never payloads, IBANs, amounts or exception messages.
 */
class NotificationPublisherScheduler {

  private static final Log LOG = LogFactory.getLog(NotificationPublisherScheduler.class);

  private final PublishPendingNotificationsInPort publishPendingNotificationsInPort;
  private final int batchSize;

  NotificationPublisherScheduler(PublishPendingNotificationsInPort publishPendingNotificationsInPort,
    int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("notification.publisher.batch-size must be at least 1");
    }
    this.publishPendingNotificationsInPort = publishPendingNotificationsInPort;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${notification.publisher.fixed-delay}",
    initialDelayString = "${notification.publisher.initial-delay}")
  void publish() {
    try {
      PublishResultDto result = publishPendingNotificationsInPort.publishPending(batchSize);
      if (result.failed()) {
        LOG.warn("Outbox publisher stopped at transfer " + result.failedTransferId() + " in stage "
          + result.failureStage() + " (sent " + result.sent() + " of " + result.read() + ")");
      }
    } catch (InvalidOutboxRowException e) {
      LOG.warn("Outbox row " + e.rowId() + " is invalid");
    } catch (RuntimeException e) {
      LOG.warn("Outbox publisher round failed: " + e.getClass().getSimpleName());
    }
  }
}
