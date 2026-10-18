package com.bank.transfer.config;

import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingHouseOutPortImpl;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingModeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClearingConfig {

  private static final Logger LOG = LoggerFactory.getLogger(ClearingConfig.class);

  /**
   * Concrete return type on purpose: tests and demos change the mode through the bean.
   */
  @Bean
  SimulatedClearingHouseOutPortImpl simulatedClearingHouseOutPort(
    @Value("${clearing.simulator.mode}") SimulatedClearingModeEnum mode,
    @Value("${clearing.outcomes.enabled:false}") String outcomesEnabled) {

    LOG.warn("Clearing house is SIMULATED (mode {}): no real clearing house is called", mode);
    if (!Boolean.parseBoolean(outcomesEnabled)) {
      LOG.warn("Clearing outcomes processing DISABLED (clearing.outcomes.enabled=false): "
        + "SENT and UNKNOWN transfers are not concluded");
    }
    return new SimulatedClearingHouseOutPortImpl(mode);
  }
}
