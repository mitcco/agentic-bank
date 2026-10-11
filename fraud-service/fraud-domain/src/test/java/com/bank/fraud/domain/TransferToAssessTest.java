package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import com.bank.fraud.domain.exception.UnsupportedCurrencyException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferToAssessTest {

    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";

    private static Money money(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    private static Iban iban1() {
        return new Iban(IBAN_1);
    }

    private static Iban iban2() {
        return new Iban(IBAN_2);
    }

    @Test
    void creaBonificoValido() {
        TransferToAssess t = new TransferToAssess(ID, iban1(), iban2(), money("1000.25", "EUR"));

        assertThat(t.transferId()).isEqualTo(ID);
        assertThat(t.source()).isEqualTo(iban1());
        assertThat(t.destination()).isEqualTo(iban2());
        assertThat(t.amount().amount()).isEqualByComparingTo("1000.25");
    }

    @Test
    void transferIdNonUuidEAccettato() {
        assertThatCode(() -> new TransferToAssess("abc", iban1(), iban2(), money("1", "EUR")))
                .doesNotThrowAnyException();
    }

    @Test
    void transferIdConSpaziIniziali_vieneConservatoInvariato() {
        TransferToAssess t = new TransferToAssess("  abc ", iban1(), iban2(), money("1", "EUR"));

        assertThat(t.transferId()).isEqualTo("  abc ");
    }

    @Test
    void mittenteUgualeADestinatarioEAccettato() {
        assertThatCode(() -> new TransferToAssess(ID, iban1(), iban1(), money("1", "EUR")))
                .doesNotThrowAnyException();
    }

    @Test
    void rifiutaTransferIdNullo() {
        assertThatThrownBy(() -> new TransferToAssess(null, iban1(), iban2(), money("1", "EUR")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaTransferIdVuoto() {
        assertThatThrownBy(() -> new TransferToAssess("", iban1(), iban2(), money("1", "EUR")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaTransferIdDiSoliSpazi() {
        assertThatThrownBy(() -> new TransferToAssess("   ", iban1(), iban2(), money("1", "EUR")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaMittenteNullo() {
        assertThatThrownBy(() -> new TransferToAssess(ID, null, iban2(), money("1", "EUR")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaDestinatarioNullo() {
        assertThatThrownBy(() -> new TransferToAssess(ID, iban1(), null, money("1", "EUR")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaImportoNullo() {
        assertThatThrownBy(() -> new TransferToAssess(ID, iban1(), iban2(), null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void rifiutaImportoZero() {
        assertThatThrownBy(() -> new TransferToAssess(ID, iban1(), iban2(), money("0", "EUR")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void valutaNonEurDaUnsupportedCurrency() {
        assertThatThrownBy(() -> new TransferToAssess(ID, iban1(), iban2(), money("100", "USD")))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessage("Only EUR is supported");
    }

    @Test
    void valutaEurMinuscolaDaUnsupportedCurrency() {
        assertThatThrownBy(() -> new TransferToAssess(ID, iban1(), iban2(), money("100", "eur")))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessage("Only EUR is supported");
    }

    @Test
    void importoZeroConValutaNonSupportata_vinceLInputNonValido() {
        assertThatThrownBy(() -> new TransferToAssess(ID, iban1(), iban2(), money("0", "USD")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void unsupportedCurrencyENonInvalidFraudRequest() {
        assertThat(InvalidFraudRequestException.class.isAssignableFrom(UnsupportedCurrencyException.class))
                .isFalse();
    }

    @Test
    void toStringEsponeSoloIlTransferId() {
        String s = new TransferToAssess(ID, iban1(), iban2(), money("1234.56", "EUR")).toString();

        assertThat(s).contains(ID).doesNotContain(IBAN_1).doesNotContain(IBAN_2)
                .doesNotContain("1234.56").doesNotContain("0542811101");
    }
}
