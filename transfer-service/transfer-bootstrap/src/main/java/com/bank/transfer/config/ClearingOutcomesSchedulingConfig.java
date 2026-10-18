package com.bank.transfer.config;

import com.bank.transfer.application.port.in.ProcessClearingOutcomesInPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "clearing.outcomes.enabled", havingValue = "true")
class ClearingOutcomesSchedulingConfig {

  @Bean
  ClearingOutcomesScheduler clearingOutcomesScheduler(ProcessClearingOutcomesInPort processClearingOutcomesInPort) {
    return new ClearingOutcomesScheduler(processClearingOutcomesInPort);
  }
}
