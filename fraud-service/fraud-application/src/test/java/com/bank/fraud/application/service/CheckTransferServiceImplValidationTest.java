package com.bank.fraud.application.service;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.exception.InvalidFraudCheckRequestException;
import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import com.bank.fraud.domain.exception.UnsupportedCurrencyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckTransferServiceImplValidationTest {

    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
    private static final String FIXED_MESSAGE = "Fraud check request is not valid";

    private final FakeBlockedIbanOutPort port = new FakeBlockedIbanOutPort();
    private final CheckTransferServiceImpl service = new CheckTransferServiceImpl(port, new BigDecimal("10000"));

    private static FraudCheckRequestDto request(String id, String source, String destination, String amount, String currency) {
        return new FraudCheckRequestDto(id, source, destination, amount == null ? null : new BigDecimal(amount), currency);
    }

    // AV1
    @ParameterizedTest
    @ValueSource(strings = {"", "IT60X054281110100000000001"})
    void ibanMittenteNonValido_lanciaInvalidFraudCheckRequestEPortaNonChiamata(String source) {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, source, IBAN_2, "100", "EUR")))
                .isInstanceOf(InvalidFraudCheckRequestException.class)
                .isInstanceOf(InvalidFraudRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void ibanDestinatarioNonValido_lanciaInvalidFraudCheckRequest() {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, "", "100", "EUR")))
                .isInstanceOf(InvalidFraudCheckRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    // AV2
    @ParameterizedTest
    @ValueSource(strings = {"0", "10.001", "10.100"})
    void importoNonValido_lanciaInvalidFraudCheckRequest(String amount) {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_2, amount, "EUR")))
                .isInstanceOf(InvalidFraudCheckRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void transferIdDiSoliSpazi_lanciaInvalidFraudCheckRequest() {
        assertThatThrownBy(() -> service.checkTransfer(request(" ", IBAN_1, IBAN_2, "100", "EUR")))
                .isInstanceOf(InvalidFraudCheckRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void valutaVuota_lanciaInvalidFraudCheckRequest() {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_2, "100", "")))
                .isInstanceOf(InvalidFraudCheckRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    // Caso 16 del contratto
    @Test
    void ibanUguali_lanciaInvalidFraudCheckRequestEPortaNonChiamata() {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_1, "100", "EUR")))
                .isInstanceOf(InvalidFraudCheckRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void ibanUgualiScrittiConSpaziEMinuscoleDiverse_lanciaInvalidFraudCheckRequestEPortaNonChiamata() {
        assertThatThrownBy(() -> service.checkTransfer(
                request(ID, IBAN_1, " it60 x054 2811 1010 0000 0000 001 ", "100", "EUR")))
                .isInstanceOf(InvalidFraudCheckRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void ibanUguali_messaggioFissoSenzaIban() {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_1, "1234.56", "EUR")))
                .isInstanceOf(InvalidFraudCheckRequestException.class)
                .hasMessage(FIXED_MESSAGE);
    }

    // AV3
    @Test
    void valutaUsd_passaInvariataComeUnsupportedCurrencyENonComeInvalidFraudCheckRequest() {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_2, "100", "USD")))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .isNotInstanceOf(InvalidFraudCheckRequestException.class)
                .hasMessage("Only EUR is supported");
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void ibanUgualiConValutaUsd_vinceLInputNonValidoENonLaValutaEPortaNonChiamata() {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_1, "100", "USD")))
                .isInstanceOf(InvalidFraudCheckRequestException.class)
                .isNotInstanceOf(UnsupportedCurrencyException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void ibanUgualiScrittiConSpaziEMinuscoleDiverseConValutaUsd_vinceLInputNonValidoENonLaValuta() {
        assertThatThrownBy(() -> service.checkTransfer(
                request(ID, IBAN_1, " it60 x054 2811 1010 0000 0000 001 ", "100", "USD")))
                .isInstanceOf(InvalidFraudCheckRequestException.class)
                .isNotInstanceOf(UnsupportedCurrencyException.class);
        assertThat(port.calls()).isEmpty();
    }

    // AV4
    @Test
    void invalidFraudRequestLanciataDallaPorta_siPropagaConTipoEsattoSenzaSottoclasse() {
        InvalidFraudRequestException fromPort = new InvalidFraudRequestException("x");
        port.throwing(fromPort);

        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_2, "100", "EUR")))
                .isSameAs(fromPort)
                .isExactlyInstanceOf(InvalidFraudRequestException.class);
    }

    // AV5
    @Test
    void importoConTroppiDecimali_messaggioFissoSenzaIbanNeImporto() {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_2, "1234.567", "EUR")))
                .isInstanceOf(InvalidFraudCheckRequestException.class)
                .hasMessage(FIXED_MESSAGE)
                .message().doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("1234.567");
    }

    @Test
    void erroreDiCostruzione_conservaLaCausaOriginaleDiDominio() {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_2, "0", "EUR")))
                .isInstanceOf(InvalidFraudCheckRequestException.class)
                .hasCauseExactlyInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void richiestaValida_nonLanciaEChiamaLaPorta() {
        assertThat(service.checkTransfer(request(ID, IBAN_1, IBAN_2, "100", "EUR")).status())
                .isEqualTo("ALLOWED");
        assertThat(port.calls()).hasSize(1);
    }
}
