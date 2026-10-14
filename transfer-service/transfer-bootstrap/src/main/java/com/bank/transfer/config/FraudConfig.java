package com.bank.transfer.config;

import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.port.out.fraud.impl.FraudRestClientFactory;
import com.bank.transfer.port.out.fraud.impl.HttpFraudCheckOutPortImpl;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FraudConfig {

  @Bean
  FraudCheckOutPort httpFraudCheckOutPort(
    @Value("${fraud.base-url}") String baseUrl,
    @Value("${fraud.connect-timeout}") Duration connectTimeout,
    @Value("${fraud.read-timeout}") Duration readTimeout) {

    return new HttpFraudCheckOutPortImpl(FraudRestClientFactory.create(baseUrl, connectTimeout, readTimeout));
  }
}
