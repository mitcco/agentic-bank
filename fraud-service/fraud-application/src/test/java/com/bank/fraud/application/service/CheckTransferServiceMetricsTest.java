package com.bank.fraud.application.service;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.dto.FraudCheckResponseDto;
import com.bank.fraud.application.exception.InvalidFraudCheckRequestException;
import com.bank.fraud.domain.FraudReasonEnum;
import com.bank.fraud.domain.FraudStatusEnum;
import com.bank.fraud.domain.exception.UnsupportedCurrencyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Metriche di business della valutazione (piano fraud-07): il service notifica la porta
 * MetricsOutPort una sola volta per valutazione completata, mai per richieste non valide
 * ne' per errori della porta degli IBAN bloccati.
 */
class CheckTransferServiceMetricsTest {

    private static final String BLOCKED = "IT60X0542811101000000000099";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
    private static final BigDecimal THRESHOLD = new BigDecimal("10000");

    private final FakeBlockedIbanOutPort blockedPort = new FakeBlockedIbanOutPort();
    private final RecordingMetricsOutPort metrics = new RecordingMetricsOutPort();
    private final CheckTransferServiceImpl service =
            new CheckTransferServiceImpl(blockedPort, THRESHOLD, metrics);

    private static FraudCheckRequestDto request(String source, String destination, String amount, String currency) {
        return new FraudCheckRequestDto(ID, source, destination,
                amount == null ? null : new BigDecimal(amount), currency);
    }

    private static FraudCheckRequestDto request(String source, String destination, String amount) {
        return request(source, destination, amount, "EUR");
    }

    // ---- valutazioni completate ----

    @Test
    void casoFelice_unaChiamataAllowedSenzaMotivi() {
        service.checkTransfer(request(IBAN_1, IBAN_2, "1000.25"));

        assertThat(metrics.calls()).containsExactly(
                new RecordingMetricsOutPort.Call(FraudStatusEnum.ALLOWED, List.of()));
    }

    @Test
    void importoUgualeAllaSoglia_unaChiamataAllowedSenzaMotivi() {
        service.checkTransfer(request(IBAN_1, IBAN_2, "10000.00"));

        assertThat(metrics.calls()).containsExactly(
                new RecordingMetricsOutPort.Call(FraudStatusEnum.ALLOWED, List.of()));
    }

    @Test
    void importoOltreLaSoglia_unaChiamataRejectedConMotivoImporto() {
        service.checkTransfer(request(IBAN_1, IBAN_2, "10000.01"));

        assertThat(metrics.calls()).containsExactly(new RecordingMetricsOutPort.Call(
                FraudStatusEnum.REJECTED, List.of(FraudReasonEnum.AMOUNT_OVER_THRESHOLD)));
    }

    @Test
    void mittenteBloccato_unaChiamataRejectedConMotivoMittente() {
        blockedPort.blocking(BLOCKED);

        service.checkTransfer(request(BLOCKED, IBAN_2, "100"));

        assertThat(metrics.calls()).containsExactly(new RecordingMetricsOutPort.Call(
                FraudStatusEnum.REJECTED, List.of(FraudReasonEnum.SOURCE_IBAN_LOCKED)));
    }

    @Test
    void destinatarioBloccato_unaChiamataRejectedConMotivoDestinatario() {
        blockedPort.blocking(BLOCKED);

        service.checkTransfer(request(IBAN_1, BLOCKED, "100"));

        assertThat(metrics.calls()).containsExactly(new RecordingMetricsOutPort.Call(
                FraudStatusEnum.REJECTED, List.of(FraudReasonEnum.DESTINATION_IBAN_LOCKED)));
    }

    @Test
    void sogliaEMittenteBloccato_unaChiamataRejectedConMotiviInOrdineImportoPoiMittente() {
        blockedPort.blocking(BLOCKED);

        service.checkTransfer(request(BLOCKED, IBAN_2, "20000.00"));

        assertThat(metrics.calls()).hasSize(1);
        assertThat(metrics.calls().get(0).status()).isEqualTo(FraudStatusEnum.REJECTED);
        assertThat(metrics.calls().get(0).reasons())
                .containsExactly(FraudReasonEnum.AMOUNT_OVER_THRESHOLD, FraudReasonEnum.SOURCE_IBAN_LOCKED);
    }

    @Test
    void entrambiBloccati_unaChiamataRejectedConMotiviMittentePoiDestinatario() {
        blockedPort.blocking(BLOCKED, IBAN_2);

        service.checkTransfer(request(BLOCKED, IBAN_2, "100"));

        assertThat(metrics.calls()).hasSize(1);
        assertThat(metrics.calls().get(0).status()).isEqualTo(FraudStatusEnum.REJECTED);
        assertThat(metrics.calls().get(0).reasons())
                .containsExactly(FraudReasonEnum.SOURCE_IBAN_LOCKED, FraudReasonEnum.DESTINATION_IBAN_LOCKED);
    }

    @Test
    void tuttiITreMotivi_unaSolaChiamataConTuttiNellOrdineDelContratto() {
        blockedPort.blocking(BLOCKED, IBAN_2);

        service.checkTransfer(request(BLOCKED, IBAN_2, "20000.00"));

        assertThat(metrics.calls()).hasSize(1);
        assertThat(metrics.calls().get(0).reasons()).containsExactly(
                FraudReasonEnum.AMOUNT_OVER_THRESHOLD,
                FraudReasonEnum.SOURCE_IBAN_LOCKED,
                FraudReasonEnum.DESTINATION_IBAN_LOCKED);
    }

    @Test
    void dueValutazioni_dueChiamateInOrdineDiArrivo() {
        service.checkTransfer(request(IBAN_1, IBAN_2, "100"));
        service.checkTransfer(request(IBAN_1, IBAN_2, "10000.01"));

        assertThat(metrics.calls()).extracting(RecordingMetricsOutPort.Call::status)
                .containsExactly(FraudStatusEnum.ALLOWED, FraudStatusEnum.REJECTED);
    }

    @Test
    void conLaPortaDelleMetriche_laRispostaNonCambia() {
        blockedPort.blocking(BLOCKED);

        FraudCheckResponseDto response = service.checkTransfer(request(BLOCKED, IBAN_2, "20000.00"));

        assertThat(response.transferId()).isEqualTo(ID);
        assertThat(response.status()).isEqualTo("REJECTED");
        assertThat(response.reasons()).containsExactly(
                "Amount must not exceed 10000 EUR", "Source IBAN is locked");
    }

    // ---- nessuna chiamata sulle richieste non valide ----

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "importo zero,          IT60X0542811101000000000001, IT60X0542811101000000000002, 0,      EUR",
            "importo negativo,      IT60X0542811101000000000001, IT60X0542811101000000000002, -1,     EUR",
            "troppi decimali,       IT60X0542811101000000000001, IT60X0542811101000000000002, 10.001, EUR",
            "iban mittente errato,  NOT-AN-IBAN,                 IT60X0542811101000000000002, 100,    EUR",
            "iban uguali,           IT60X0542811101000000000001, IT60X0542811101000000000001, 100,    EUR"
    })
    void richiestaNonValida_lanciaInvalidFraudCheckRequestENessunaChiamataAlleMetriche(
            String name, String source, String destination, String amount, String currency) {
        assertThatThrownBy(() -> service.checkTransfer(request(source, destination, amount, currency)))
                .isInstanceOf(InvalidFraudCheckRequestException.class);

        assertThat(metrics.calls()).isEmpty();
        assertThat(blockedPort.calls()).isEmpty();
    }

    @Test
    void valutaUsd_lanciaUnsupportedCurrencyENessunaChiamataAlleMetriche() {
        assertThatThrownBy(() -> service.checkTransfer(request(IBAN_1, IBAN_2, "100", "USD")))
                .isInstanceOf(UnsupportedCurrencyException.class);

        assertThat(metrics.calls()).isEmpty();
    }

    @Test
    void ibanUgualiScrittiConSpaziEMinuscoleDiverse_nessunaChiamataAlleMetriche() {
        assertThatThrownBy(() -> service.checkTransfer(
                request(IBAN_1, " it60 x054 2811 1010 0000 0000 001 ", "100")))
                .isInstanceOf(InvalidFraudCheckRequestException.class);

        assertThat(metrics.calls()).isEmpty();
    }

    // ---- nessuna chiamata sugli errori della porta degli IBAN bloccati ----

    @Test
    void portaBloccatiCheLancia_siPropagaENessunaChiamataAlleMetriche() {
        IllegalStateException failure = new IllegalStateException("down");
        blockedPort.throwing(failure);

        assertThatThrownBy(() -> service.checkTransfer(request(IBAN_1, IBAN_2, "100")))
                .isSameAs(failure);

        assertThat(metrics.calls()).isEmpty();
    }

    @Test
    void portaBloccatiCheRestituisceNull_nullPointerExceptionENessunaChiamataAlleMetriche() {
        blockedPort.returningNull();

        assertThatThrownBy(() -> service.checkTransfer(request(IBAN_1, IBAN_2, "100")))
                .isInstanceOf(NullPointerException.class);

        assertThat(metrics.calls()).isEmpty();
    }

    // ---- costruttori ----

    @Test
    void costruttoreATreArgomentiConPortaMetricheNull_lanciaNullPointerExceptionConIlNomeDelCampo() {
        assertThatThrownBy(() -> new CheckTransferServiceImpl(blockedPort, THRESHOLD, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metricsOutPort");
    }
}
