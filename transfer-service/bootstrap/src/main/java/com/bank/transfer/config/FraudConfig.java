package com.bank.transfer.config;

import com.bank.transfer.adapter.out.fraud.StubFraudCheck;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.Iban;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class FraudConfig {

  @Bean
  FraudCheckOutPort fraudCheckOutPort(
    @Value("${fraud.stub.threshold:10000}") BigDecimal threshold,
    @Value("${fraud.stub.blocked-ibans:}") List<String> blockedIbans) {

    Set<Iban> blocked = blockedIbans.stream()
                          .filter(s -> !s.isBlank())
                          .map(Iban::new)
                          .collect(Collectors.toSet());

    return new StubFraudCheck(threshold, blocked);
  }
}
