package com.bank.transfer.config;

import com.bank.transfer.domain.BankCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TransferRoutingConfig {

  @Bean
  BankCode bankCode(@Value("${transfer.bank-code}") String value) {
    return new BankCode(value);
  }
}
