package com.bank.transfer.config;

import com.bank.transfer.application.port.in.ReconcileExternalTransfersInPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodic round that asks the clearing house about the stale SENT and UNKNOWN transfers. Never throws: the next
 * round is the retry. Logs only the number of transfers and the simple class name of an exception, never
 * exception messages.
 * Warning: {@code stale-after} must never be set close to zero in production. If the reconciliation asks before the
 * {@code submit} of a freshly debited transfer has reached the clearing house, the answer is "not found", the
 * transfer is refunded and the clearing house may then accept the instruction (double movement).
 */
class ClearingReconciliationScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(ClearingReconciliationScheduler.class);

  private final ReconcileExternalTransfersInPort reconcileExternalTransfersInPort;
  private final Clock clock;
  private final Duration staleAfter;
  private final int batchSize;

  ClearingReconciliationScheduler(ReconcileExternalTransfersInPort reconcileExternalTransfersInPort, Clock clock,
    Duration staleAfter, int batchSize) {
    this.reconcileExternalTransfersInPort =
      Objects.requireNonNull(reconcileExternalTransfersInPort, "reconcileExternalTransfersInPort");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter");
    if (staleAfter.isZero() || staleAfter.isNegative()) {
      throw new IllegalArgumentException("staleAfter must be positive");
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1");
    }
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${clearing.reconcile.fixed-delay}",
    initialDelayString = "${clearing.reconcile.initial-delay}")
  void reconcile() {
    try {
      Instant threshold = clock.instant().minus(staleAfter);
      int applied = reconcileExternalTransfersInPort.reconcileStale(threshold, batchSize);
      if (applied > 0) {
        LOG.info("Clearing reconciliation applied: {}", applied);
      }
    } catch (RuntimeException e) {
      LOG.warn("Clearing reconciliation round failed: {}", e.getClass().getSimpleName());
    }
  }
}
