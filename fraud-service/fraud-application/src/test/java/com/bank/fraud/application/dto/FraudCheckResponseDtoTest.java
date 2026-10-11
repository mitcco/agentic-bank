package com.bank.fraud.application.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCheckResponseDtoTest {

    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";

    @Test
    void listaMutabileModificataDopoLaCreazione_reasonsRestaInvariata() {
        List<String> source = new ArrayList<>(List.of("Source IBAN is locked"));
        FraudCheckResponseDto dto = new FraudCheckResponseDto(ID, "REJECTED", source);

        source.add("Destination IBAN is locked");

        assertThat(dto.reasons()).containsExactly("Source IBAN is locked");
    }

    @Test
    void reasonsRestituitaNonModificabile() {
        FraudCheckResponseDto dto = new FraudCheckResponseDto(ID, "REJECTED", List.of("Source IBAN is locked"));

        assertThatThrownBy(() -> dto.reasons().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }
}
