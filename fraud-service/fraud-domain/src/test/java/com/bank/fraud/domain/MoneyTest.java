package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.DifferentCurrencyException;
import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static Money eur(String amount) {
        return new Money(new BigDecimal(amount), "EUR");
    }

    @Test
    void importiConScaleDiversaMaStessoValoreSonoUguali() {
        Money a = eur("2.0");
        Money b = eur("2.00");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void importiDiversiNonSonoUguali() {
        assertThat(eur("2.00")).isNotEqualTo(eur("2.01"));
    }

    @Test
    void valuteDiverseNonSonoUguali() {
        assertThat(eur("2.00")).isNotEqualTo(new Money(new BigDecimal("2.00"), "USD"));
    }

    @Test
    void importoZeroEAmmesso() {
        Money money = eur("0");

        assertThat(money.amount()).isEqualByComparingTo("0");
    }

    @Test
    void accettaImportiValidi() {
        assertThat(eur("10.01").amount()).isEqualByComparingTo("10.01");
        assertThat(eur("10000").amount()).isEqualByComparingTo("10000");
    }

    @Test
    void valutaNonViene_normalizzataNeControllata() {
        assertThat(new Money(new BigDecimal("1"), "eur").currency()).isEqualTo("eur");
        assertThat(new Money(new BigDecimal("1"), "USD").currency()).isEqualTo("USD");
    }

    @Test
    void importoMaggioreDellaSogliaESuperiore() {
        assertThat(eur("10000.01").isGreaterThan(eur("10000"))).isTrue();
    }

    @Test
    void importoUgualeAllaSogliaConScaleDiversaNonESuperiore() {
        assertThat(eur("10000.00").isGreaterThan(eur("10000"))).isFalse();
    }

    @Test
    void importoMinoreDellaSogliaNonESuperiore() {
        assertThat(eur("9999.99").isGreaterThan(eur("10000"))).isFalse();
    }

    @Test
    void rifiutaImportoNullo() {
        assertThatThrownBy(() -> new Money(null, "EUR")).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaImportoNegativo() {
        assertThatThrownBy(() -> eur("-1")).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaPiuDiDueDecimali() {
        assertThatThrownBy(() -> eur("10.001")).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaTreDecimaliAncheConZeroFinale() {
        assertThatThrownBy(() -> eur("10.100")).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaValutaNulla() {
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaValutaVuota() {
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, ""))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaValutaDiSoliSpazi() {
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, "  "))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void confrontoTraValuteDiverseLanciaDifferentCurrencyException() {
        Money euro = eur("1");
        Money dollari = new Money(BigDecimal.ONE, "USD");

        assertThatThrownBy(() -> euro.isGreaterThan(dollari)).isInstanceOf(DifferentCurrencyException.class);
    }

    @Test
    void confrontoConImportoNulloLanciaInvalidFraudRequestException() {
        Money money = eur("1");

        assertThatThrownBy(() -> money.isGreaterThan(null)).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void messaggiDiErroreNonContengonoLImporto() {
        assertThatThrownBy(() -> eur("1234.567"))
                .isInstanceOf(InvalidFraudRequestException.class)
                .message().doesNotContain("1234");
        assertThatThrownBy(() -> eur("-1234.56"))
                .isInstanceOf(InvalidFraudRequestException.class)
                .message().doesNotContain("1234");
    }

    @Test
    void toStringNonContieneLImporto() {
        assertThat(eur("1234.56").toString()).doesNotContain("1234.56").doesNotContain("1234");
    }
}
