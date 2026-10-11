package com.bank.transfer.config;

import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.port.out.fraud.impl.FraudRestClientFactory;
import com.bank.transfer.port.out.fraud.impl.HttpFraudCheckOutPortImpl;
import com.bank.transfer.port.out.fraud.impl.StubFraudCheck;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
class FraudConfig {

  @Bean
  @Profile("inmemory")
  FraudCheckOutPort stubFraudCheckOutPort(
    @Value("${fraud.stub.threshold:10000}") BigDecimal threshold,
    @Value("${fraud.stub.blocked-ibans:}") List<String> blockedIbans) {

    Set<Iban> blocked = blockedIbans.stream()
                          .filter(s -> !s.isBlank())
                          .map(Iban::new)
                          .collect(Collectors.toSet());

    return new StubFraudCheck(threshold, blocked);
  }

  @Bean
  @Profile("!inmemory")
  FraudCheckOutPort httpFraudCheckOutPort(
    @Value("${fraud.base-url}") String baseUrl,
    @Value("${fraud.connect-timeout}") Duration connectTimeout,
    @Value("${fraud.read-timeout}") Duration readTimeout) {

    return new HttpFraudCheckOutPortImpl(FraudRestClientFactory.create(baseUrl, connectTimeout, readTimeout));
  }
}
