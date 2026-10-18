package com.bank.transfer.config;

import com.bank.transfer.application.port.in.ProcessClearingOutcomesInPort;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Periodic round that applies the outcomes of the clearing house. Never throws: the next round is the retry.
 * Logs only the number of outcomes and the simple class name of an exception, never exception messages.
 */
class ClearingOutcomesScheduler {

  private static final Logger LOG = LoggerFactory.getLogger(ClearingOutcomesScheduler.class);

  private final ProcessClearingOutcomesInPort processClearingOutcomesInPort;

  ClearingOutcomesScheduler(ProcessClearingOutcomesInPort processClearingOutcomesInPort) {
    this.processClearingOutcomesInPort =
      Objects.requireNonNull(processClearingOutcomesInPort, "processClearingOutcomesInPort");
  }

  @Scheduled(fixedDelayString = "${clearing.outcomes.fixed-delay}",
    initialDelayString = "${clearing.outcomes.initial-delay}")
  void processOutcomes() {
    try {
      int applied = processClearingOutcomesInPort.processOutcomes();
      if (applied > 0) {
        LOG.info("Clearing outcomes applied: {}", applied);
      }
    } catch (RuntimeException e) {
      LOG.warn("Clearing outcomes round failed: {}", e.getClass().getSimpleName());
    }
  }
}
