package com.bank.transfer.config;

import com.bank.transfer.application.port.out.MetricsOutPort;
import com.bank.transfer.micrometer.port.out.micrometer.impl.MicrometerMetricsOutPortImpl;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MetricsConfig {

  @Bean
  MetricsOutPort metricsOutPort(MeterRegistry registry) {
    return new MicrometerMetricsOutPortImpl(registry);
  }
}
