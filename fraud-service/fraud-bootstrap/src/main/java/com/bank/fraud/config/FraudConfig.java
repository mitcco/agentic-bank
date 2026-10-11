package com.bank.fraud.config;

import com.bank.fraud.application.port.in.CheckTransferInPort;
import com.bank.fraud.application.port.out.BlockedIbanOutPort;
import com.bank.fraud.application.service.CheckTransferServiceImpl;
import com.bank.fraud.persistence.port.out.persistence.impl.inmemory.InMemoryBlockedIbanOutPortImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Configuration
class FraudConfig {

    @Bean
    BlockedIbanOutPort blockedIbanOutPort(@Value("${fraud.blocked-ibans}") String[] blockedIbans) {
        List<String> filtered = Arrays.stream(blockedIbans)
                .filter(value -> !value.isBlank())
                .toList();
        return new InMemoryBlockedIbanOutPortImpl(filtered);
    }

    @Bean
    CheckTransferInPort checkTransferInPort(BlockedIbanOutPort blockedIbanOutPort,
                                            @Value("${fraud.threshold}") BigDecimal threshold) {
        return new CheckTransferServiceImpl(blockedIbanOutPort, threshold);
    }
}
