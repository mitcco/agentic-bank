package com.bank.fraud.application.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FraudCheckRequestDtoTest {

    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";

    @Test
    void toString_contieneTransferIdMaNonIbanNeImporto() {
        FraudCheckRequestDto dto = new FraudCheckRequestDto(
                ID, "IT60X0542811101000000000001", "IT60X0542811101000000000002",
                new BigDecimal("1234.56"), "EUR");

        String text = dto.toString();

        assertThat(text).contains(ID);
        assertThat(text).doesNotContain("IT60X0542811101000000000001");
        assertThat(text).doesNotContain("IT60X0542811101000000000002");
        assertThat(text).doesNotContain("1234.56");
    }
}
