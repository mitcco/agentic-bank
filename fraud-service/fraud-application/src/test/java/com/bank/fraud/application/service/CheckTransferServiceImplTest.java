package com.bank.fraud.application.service;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.dto.FraudCheckResponseDto;
import com.bank.fraud.domain.Iban;
import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import com.bank.fraud.domain.exception.UnsupportedCurrencyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckTransferServiceImplTest {

    private static final String BLOCKED = "IT60X0542811101000000000099";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";
    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";

    private static final String AMOUNT_MSG = "Amount must not exceed 10000 EUR";
    private static final String SOURCE_MSG = "Source IBAN is locked";
    private static final String DEST_MSG = "Destination IBAN is locked";

    private final FakeBlockedIbanOutPort port = new FakeBlockedIbanOutPort();
    private final CheckTransferServiceImpl service = new CheckTransferServiceImpl(port, new BigDecimal("10000"));

    private static FraudCheckRequestDto request(String id, String source, String destination, String amount, String currency) {
        return new FraudCheckRequestDto(id, source, destination, amount == null ? null : new BigDecimal(amount), currency);
    }

    private static FraudCheckRequestDto request(String source, String destination, String amount) {
        return request(ID, source, destination, amount, "EUR");
    }

    // ---- casi felici e regole ----

    @Test
    void casoFelice_importoOrdinarioNessunBloccato_allowedConReasonsVuota() {
        FraudCheckResponseDto r = service.checkTransfer(request(IBAN_1, IBAN_2, "1000.25"));

        assertThat(r.transferId()).isEqualTo(ID);
        assertThat(r.status()).isEqualTo("ALLOWED");
        assertThat(r.reasons()).isNotNull().isEmpty();
    }

    @Test
    void importoUgualeAllaSoglia_allowed() {
        FraudCheckResponseDto r = service.checkTransfer(request(IBAN_1, IBAN_2, "10000.00"));

        assertThat(r.status()).isEqualTo("ALLOWED");
        assertThat(r.reasons()).isEmpty();
    }

    @Test
    void importoOltreLaSoglia_rejectedConMotivoImporto() {
        FraudCheckResponseDto r = service.checkTransfer(request(IBAN_1, IBAN_2, "10000.01"));

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.reasons()).containsExactly(AMOUNT_MSG);
    }

    @Test
    void mittenteBloccato_rejectedConMotivoMittente() {
        port.blocking(BLOCKED);

        FraudCheckResponseDto r = service.checkTransfer(request(BLOCKED, IBAN_2, "100"));

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.reasons()).containsExactly(SOURCE_MSG);
    }

    @Test
    void destinatarioBloccato_rejectedConMotivoDestinatario() {
        port.blocking(BLOCKED);

        FraudCheckResponseDto r = service.checkTransfer(request(IBAN_1, BLOCKED, "100"));

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.reasons()).containsExactly(DEST_MSG);
    }

    @Test
    void entrambiBloccati_motiviMittentePoiDestinatario() {
        port.blocking(BLOCKED, IBAN_2);

        FraudCheckResponseDto r = service.checkTransfer(request(BLOCKED, IBAN_2, "100"));

        assertThat(r.reasons()).containsExactly(SOURCE_MSG, DEST_MSG);
    }

    @Test
    void sogliaEMittenteBloccato_motiviImportoPoiMittente() {
        port.blocking(BLOCKED);

        FraudCheckResponseDto r = service.checkTransfer(request(BLOCKED, IBAN_2, "20000.00"));

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.reasons()).containsExactly(AMOUNT_MSG, SOURCE_MSG);
    }

    @Test
    void tuttiITreMotivi_inOrdineImportoMittenteDestinatario() {
        port.blocking(BLOCKED, IBAN_2);

        FraudCheckResponseDto r = service.checkTransfer(request(BLOCKED, IBAN_2, "20000.00"));

        assertThat(r.reasons()).containsExactly(AMOUNT_MSG, SOURCE_MSG, DEST_MSG);
    }

    @Test
    void stessaRichiestaDueVolte_stessaRisposta() {
        FraudCheckRequestDto req = request(IBAN_1, IBAN_2, "10000.01");

        FraudCheckResponseDto first = service.checkTransfer(req);
        FraudCheckResponseDto second = service.checkTransfer(req);

        assertThat(second).isEqualTo(first);
        assertThat(first.reasons()).containsExactly(AMOUNT_MSG);
    }

    @Test
    void transferIdQualsiasiStringaNonVuota_restituitoIdentico() {
        assertThat(service.checkTransfer(request("abc", IBAN_1, IBAN_2, "100", "EUR")).transferId())
                .isEqualTo("abc");
        assertThat(service.checkTransfer(request(" x ", IBAN_1, IBAN_2, "100", "EUR")).transferId())
                .isEqualTo(" x ");
    }

    @Test
    void transferIdDiversi_stessoStatusEStessiMotivi() {
        port.blocking(BLOCKED);

        FraudCheckResponseDto a = service.checkTransfer(request("id-1", BLOCKED, IBAN_2, "20000", "EUR"));
        FraudCheckResponseDto b = service.checkTransfer(request("id-2", BLOCKED, IBAN_2, "20000", "EUR"));

        assertThat(a.transferId()).isNotEqualTo(b.transferId());
        assertThat(a.status()).isEqualTo(b.status());
        assertThat(a.reasons()).isEqualTo(b.reasons());
    }

    // ---- soglia configurabile ----

    @Test
    void sogliaConfigurata5000_testoDelMotivoUsaLaSogliaConfigurata() {
        CheckTransferServiceImpl s = new CheckTransferServiceImpl(port, new BigDecimal("5000"));

        FraudCheckResponseDto r = s.checkTransfer(request(IBAN_1, IBAN_2, "6000"));

        assertThat(r.reasons()).containsExactly("Amount must not exceed 5000 EUR");
    }

    @Test
    void sogliaConZeriFinali_testoDelMotivoSenzaZeriFinali() {
        CheckTransferServiceImpl s = new CheckTransferServiceImpl(port, new BigDecimal("10000.00"));

        FraudCheckResponseDto r = s.checkTransfer(request(IBAN_1, IBAN_2, "10000.01"));

        assertThat(r.reasons()).containsExactly("Amount must not exceed 10000 EUR");
    }

    // ---- interazione con la porta ----

    @Test
    void ibanScrittoInModoDiverso_vieneNormalizzatoEConfrontatoConLaPorta() {
        port.blocking(BLOCKED);

        FraudCheckResponseDto r = service.checkTransfer(
                request(" it60 x054 2811 1010 0000 0000 099 ", IBAN_2, "100"));

        assertThat(r.reasons()).containsExactly(SOURCE_MSG);
        assertThat(port.calls()).hasSize(1);
        assertThat(port.calls().get(0)).contains(new Iban(BLOCKED));
    }

    @Test
    void portaChiamataUnaSolaVoltaConMittenteEDestinatario() {
        service.checkTransfer(request(IBAN_1, IBAN_2, "100"));

        assertThat(port.calls()).hasSize(1);
        assertThat(port.calls().get(0)).containsExactlyInAnyOrder(new Iban(IBAN_1), new Iban(IBAN_2));
    }

    @Test
    void mittenteUgualeDestinatarioBloccato_nessunaEccezioneDueMotiviEInsiemeDiUnElemento() {
        port.blocking(BLOCKED);

        FraudCheckResponseDto r = service.checkTransfer(request(BLOCKED, BLOCKED, "100"));

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.reasons()).containsExactly(SOURCE_MSG, DEST_MSG);
        assertThat(port.calls()).hasSize(1);
        assertThat(port.calls().get(0)).hasSize(1);
    }

    @Test
    void portaRestituisceIbanEstranei_allowed() {
        port.blocking(BLOCKED);

        FraudCheckResponseDto r = service.checkTransfer(request(IBAN_1, IBAN_2, "100"));

        assertThat(r.status()).isEqualTo("ALLOWED");
        assertThat(r.reasons()).isEmpty();
    }

    // ---- errori propagati dal dominio ----

    @ParameterizedTest
    @ValueSource(strings = {"USD", "eur"})
    void valutaNonSupportata_eccezioneDiDominioEPortaNonChiamata(String currency) {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_2, "100", currency)))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessage("Only EUR is supported");
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void importoZero_invalidRequestEPortaNonChiamata() {
        assertThatThrownBy(() -> service.checkTransfer(request(IBAN_1, IBAN_2, "0")))
                .isInstanceOf(InvalidFraudRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void importoConTroppiDecimali_invalidRequestEPortaNonChiamata() {
        assertThatThrownBy(() -> service.checkTransfer(request(IBAN_1, IBAN_2, "10.001")))
                .isInstanceOf(InvalidFraudRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void ibanMittenteVuoto_invalidRequestEPortaNonChiamata() {
        assertThatThrownBy(() -> service.checkTransfer(request("", IBAN_2, "100")))
                .isInstanceOf(InvalidFraudRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void transferIdVuoto_invalidRequestEPortaNonChiamata() {
        assertThatThrownBy(() -> service.checkTransfer(request("", IBAN_1, IBAN_2, "100", "EUR")))
                .isInstanceOf(InvalidFraudRequestException.class);
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void campoNullo_invalidRequestEPortaNonChiamata() {
        List<FraudCheckRequestDto> requests = List.of(
                request(null, IBAN_1, IBAN_2, "100", "EUR"),
                request(ID, null, IBAN_2, "100", "EUR"),
                request(ID, IBAN_1, null, "100", "EUR"),
                request(ID, IBAN_1, IBAN_2, null, "EUR"),
                request(ID, IBAN_1, IBAN_2, "100", null));

        for (FraudCheckRequestDto req : requests) {
            assertThatThrownBy(() -> service.checkTransfer(req))
                    .isInstanceOf(InvalidFraudRequestException.class);
        }
        assertThat(port.calls()).isEmpty();
    }

    @Test
    void messaggioDiErrore_nonContieneIbanNeImporto() {
        FraudCheckRequestDto req = request(ID, IBAN_1, IBAN_2, "1234.567", "EUR");

        assertThatThrownBy(() -> service.checkTransfer(req))
                .isInstanceOf(InvalidFraudRequestException.class)
                .message().doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("1234.567");
    }

    @Test
    void importoZeroConValutaUsd_invalidRequestPerchePrimaSiControllaLImporto() {
        assertThatThrownBy(() -> service.checkTransfer(request(ID, IBAN_1, IBAN_2, "0", "USD")))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    // ---- fail closed sulla porta ----

    @Test
    void portaLanciaEccezione_vienePropagataCosiCome() {
        RuntimeException boom = new RuntimeException("boom");
        port.throwing(boom);

        assertThatThrownBy(() -> service.checkTransfer(request(IBAN_1, IBAN_2, "100")))
                .isSameAs(boom);
    }

    @Test
    void portaRestituisceNull_failClosedConNullPointerException() {
        port.returningNull();

        assertThatThrownBy(() -> service.checkTransfer(request(IBAN_1, IBAN_2, "100")))
                .isInstanceOf(NullPointerException.class)
                .isNotInstanceOf(InvalidFraudRequestException.class)
                .isNotInstanceOf(UnsupportedCurrencyException.class);
    }

    // ---- costruttore ----

    @Test
    void sogliaNulla_invalidRequest() {
        assertThatThrownBy(() -> new CheckTransferServiceImpl(port, null))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10.001"})
    void sogliaNonValida_invalidRequest(String threshold) {
        assertThatThrownBy(() -> new CheckTransferServiceImpl(port, new BigDecimal(threshold)))
                .isInstanceOf(InvalidFraudRequestException.class);
    }

    @Test
    void portaNulla_nullPointerException() {
        assertThatThrownBy(() -> new CheckTransferServiceImpl(null, new BigDecimal("10000")))
                .isInstanceOf(NullPointerException.class);
    }
}
