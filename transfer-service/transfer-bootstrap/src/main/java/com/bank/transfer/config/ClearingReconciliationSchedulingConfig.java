package com.bank.transfer.config;

import com.bank.transfer.application.port.in.ReconcileExternalTransfersInPort;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "clearing.outcomes.enabled", havingValue = "true")
class ClearingReconciliationSchedulingConfig {

  @Bean
  ClearingReconciliationScheduler clearingReconciliationScheduler(
    ReconcileExternalTransfersInPort reconcileExternalTransfersInPort, Clock clock,
    @Value("${clearing.reconcile.stale-after}") Duration staleAfter,
    @Value("${clearing.reconcile.batch-size}") int batchSize) {
    return new ClearingReconciliationScheduler(reconcileExternalTransfersInPort, clock, staleAfter, batchSize);
  }
}
