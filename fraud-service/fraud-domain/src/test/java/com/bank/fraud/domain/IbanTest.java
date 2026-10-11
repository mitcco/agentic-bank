package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IbanTest {

    private static final String IBAN_1 = "IT60X0542811101000000000001";

    @Test
    void normalizzaSpaziEMinuscole() {
        Iban iban = new Iban(" it60 x054 2811 1010 0000 0000 001 ");

        assertThat(iban.value()).isEqualTo(IBAN_1);
    }

    @Test
    void ibanNormalizzatoEUgualeAOriginaleConStessoHashCode() {
        Iban a = new Iban(IBAN_1);
        Iban b = new Iban(" it60 x054 2811 1010 0000 0000 001 ");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void rimuoveTabEACapoInterni() {
        Iban iban = new Iban("IT60X054\t2811101000\n000000001");

        assertThat(iban.value()).isEqualTo(IBAN_1);
    }

    @Test
    void rifiutaIbanNullo() {
        assertThatThrownBy(() -> new Iban(null)).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaIbanVuoto() {
        assertThatThrownBy(() -> new Iban("")).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaIbanDiSoliSpazi() {
        assertThatThrownBy(() -> new Iban("   ")).isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaIbanDi26Caratteri() {
        assertThatThrownBy(() -> new Iban("IT60X054281110100000000001"))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaIbanDi28Caratteri() {
        assertThatThrownBy(() -> new Iban("IT60X05428111010000000000011"))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaCaratteriNonAlfanumerici() {
        assertThatThrownBy(() -> new Iban("IT60-0542811101000000000001"))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaPaeseNonAlfabetico() {
        assertThatThrownBy(() -> new Iban("1T60X0542811101000000000001"))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaCifreDiControlloNonNumeriche() {
        assertThatThrownBy(() -> new Iban("ITX0X0542811101000000000001"))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void messaggioDiErroreNonContieneIlValoreRicevuto() {
        String invalid = "IT60X054281110100000000001";

        assertThatThrownBy(() -> new Iban(invalid))
                .isInstanceOf(InvalidFraudRequestException.class)
                .message().doesNotContain(invalid).doesNotContain("0542811101");
    }

    @Test
    void toStringNonContieneLIban() {
        assertThat(new Iban(IBAN_1).toString()).doesNotContain(IBAN_1).doesNotContain("0542811101");
    }
}
