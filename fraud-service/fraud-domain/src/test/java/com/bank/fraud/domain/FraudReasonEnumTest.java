package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudReasonEnumTest {

    private static Money eur(String amount) {
        return new Money(new BigDecimal(amount), "EUR");
    }

    @Test
    void motivoSogliaUsaLaSogliaConfigurata() {
        assertThat(FraudReasonEnum.AMOUNT_OVER_THRESHOLD.message(eur("10000")))
                .isEqualTo("Amount must not exceed 10000 EUR");
    }

    @Test
    void motivoSogliaConSoglia5000() {
        assertThat(FraudReasonEnum.AMOUNT_OVER_THRESHOLD.message(eur("5000")))
                .isEqualTo("Amount must not exceed 5000 EUR");
    }

    @Test
    void motivoSogliaSenzaZeriFinali() {
        assertThat(FraudReasonEnum.AMOUNT_OVER_THRESHOLD.message(eur("10000.00")))
                .isEqualTo("Amount must not exceed 10000 EUR");
    }

    @Test
    void motivoSogliaNonUsaNotazioneScientifica() {
        assertThat(FraudReasonEnum.AMOUNT_OVER_THRESHOLD.message(eur("1E+4")))
                .isEqualTo("Amount must not exceed 10000 EUR");
    }

    @Test
    void motivoMittenteBloccatoIgnoraLaSoglia() {
        assertThat(FraudReasonEnum.SOURCE_IBAN_LOCKED.message(eur("10000")))
                .isEqualTo("Source IBAN is locked");
    }

    @Test
    void motivoDestinatarioBloccatoIgnoraLaSoglia() {
        assertThat(FraudReasonEnum.DESTINATION_IBAN_LOCKED.message(eur("10000")))
                .isEqualTo("Destination IBAN is locked");
    }

    @Test
    void motivoSogliaConSogliaNullaLanciaInvalidFraudRequestException() {
        assertThatThrownBy(() -> FraudReasonEnum.AMOUNT_OVER_THRESHOLD.message(null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void motivoMittenteBloccatoConSogliaNullaLanciaInvalidFraudRequestException() {
        assertThatThrownBy(() -> FraudReasonEnum.SOURCE_IBAN_LOCKED.message(null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void motivoDestinatarioBloccatoConSogliaNullaLanciaInvalidFraudRequestException() {
        assertThatThrownBy(() -> FraudReasonEnum.DESTINATION_IBAN_LOCKED.message(null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void ordineDeiMotiviEQuelloDelContratto() {
        assertThat(FraudReasonEnum.values()).containsExactly(
                FraudReasonEnum.AMOUNT_OVER_THRESHOLD,
                FraudReasonEnum.SOURCE_IBAN_LOCKED,
                FraudReasonEnum.DESTINATION_IBAN_LOCKED);
    }
}
