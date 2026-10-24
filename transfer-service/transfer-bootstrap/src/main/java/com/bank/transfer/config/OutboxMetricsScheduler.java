package com.bank.transfer.config;

import com.bank.transfer.application.port.in.RefreshOutboxMetricsInPort;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodic refresh of the outbox gauge. Never throws: the next round is the retry. Logs only the simple class name
 * of an exception, never its message.
 */
class OutboxMetricsScheduler {

  private static final Log LOG = LogFactory.getLog(OutboxMetricsScheduler.class);

  private final RefreshOutboxMetricsInPort refreshOutboxMetricsInPort;

  OutboxMetricsScheduler(RefreshOutboxMetricsInPort refreshOutboxMetricsInPort) {
    this.refreshOutboxMetricsInPort = refreshOutboxMetricsInPort;
  }

  @Scheduled(fixedDelayString = "${transfer.metrics.outbox.fixed-delay}",
    initialDelayString = "${transfer.metrics.outbox.initial-delay}")
  void refreshPendingNotifications() {
    try {
      refreshOutboxMetricsInPort.refreshPendingNotifications();
    } catch (RuntimeException e) {
      LOG.warn("Outbox metrics refresh failed: " + e.getClass().getSimpleName());
    }
  }
}
