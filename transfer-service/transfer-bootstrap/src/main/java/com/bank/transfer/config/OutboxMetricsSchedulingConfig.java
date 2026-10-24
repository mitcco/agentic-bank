package com.bank.transfer.config;

import com.bank.transfer.application.port.in.RefreshOutboxMetricsInPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "transfer.metrics.outbox.enabled", havingValue = "true")
class OutboxMetricsSchedulingConfig {

  @Bean
  OutboxMetricsScheduler outboxMetricsScheduler(RefreshOutboxMetricsInPort refreshOutboxMetricsInPort) {
    return new OutboxMetricsScheduler(refreshOutboxMetricsInPort);
  }
}
