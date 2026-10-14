package com.bank.fraud.rest.servicedto.internal;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.dto.FraudCheckResponseDto;
import com.bank.fraud.application.port.in.CheckTransferInPort;
import com.bank.fraud.domain.exception.UnsupportedCurrencyException;
import com.bank.fraud.rest.dto.request.FraudCheckHttpRequestDto;
import com.bank.fraud.rest.dto.response.FraudCheckHttpResponseDto;
import com.bank.fraud.rest.mapper.FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapperImpl;
import com.bank.fraud.rest.mapper.FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapperImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudCheckServiceDtoImplTest {

    private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
    private static final String IBAN_1 = "IT60X0542811101000000000001";
    private static final String IBAN_2 = "IT60X0542811101000000000002";

    /** Fake locale: registra le richieste e risponde o lancia come configurato. */
    private static final class RecordingPort implements CheckTransferInPort {
        final List<FraudCheckRequestDto> requests = new ArrayList<>();
        FraudCheckResponseDto response = new FraudCheckResponseDto(ID, "ALLOWED", List.of());
        RuntimeException toThrow;

        @Override
        public FraudCheckResponseDto checkTransfer(FraudCheckRequestDto request) {
            requests.add(request);
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private final RecordingPort port = new RecordingPort();
    private final FraudCheckServiceDtoImpl serviceDto = new FraudCheckServiceDtoImpl(
            port,
            new FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapperImpl(),
            new FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapperImpl());

    private static FraudCheckHttpRequestDto httpRequest() {
        return new FraudCheckHttpRequestDto(ID, IBAN_1, IBAN_2, new BigDecimal("10.10"), "EUR");
    }

    // SD1
    @Test
    void laPortaRiceveGliStessiCinqueValoriEImportoConservaLaScala() {
        serviceDto.checkTransfer(httpRequest());

        assertThat(port.requests).hasSize(1);
        FraudCheckRequestDto received = port.requests.get(0);
        assertThat(received.transferId()).isEqualTo(ID);
        assertThat(received.sourceIban()).isEqualTo(IBAN_1);
        assertThat(received.destinationIban()).isEqualTo(IBAN_2);
        assertThat(received.currency()).isEqualTo("EUR");
        assertThat(received.amount()).isEqualByComparingTo("10.10");
        assertThat(received.amount().scale()).isEqualTo(2);
    }

    // SD2
    @Test
    void rispostaRejectedConDueMotivi_stessiTransferIdStatusEMotiviInOrdine() {
        port.response = new FraudCheckResponseDto(ID, "REJECTED",
                List.of("Amount must not exceed 10000 EUR", "Source IBAN is locked"));

        FraudCheckHttpResponseDto r = serviceDto.checkTransfer(httpRequest());

        assertThat(r.transferId()).isEqualTo(ID);
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.reasons()).containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
    }

    // SD3
    @Test
    void rispostaAllowed_reasonsVuota() {
        FraudCheckHttpResponseDto r = serviceDto.checkTransfer(httpRequest());

        assertThat(r.transferId()).isEqualTo(ID);
        assertThat(r.status()).isEqualTo("ALLOWED");
        assertThat(r.reasons()).isEmpty();
    }

    // SD4
    @Test
    void unsupportedCurrencyDallaPorta_siPropagaInvariata() {
        UnsupportedCurrencyException e = new UnsupportedCurrencyException("Only EUR is supported");
        port.toThrow = e;

        assertThatThrownBy(() -> serviceDto.checkTransfer(httpRequest())).isSameAs(e);
    }

    @Test
    void runtimeExceptionDallaPorta_siPropagaInvariata() {
        RuntimeException e = new RuntimeException("boom");
        port.toThrow = e;

        assertThatThrownBy(() -> serviceDto.checkTransfer(httpRequest())).isSameAs(e);
    }

    // DTO HTTP: toString espone solo il transferId
    @Test
    void toStringDelDtoDiRichiestaHttp_esponeSoloIlTransferId() {
        String s = httpRequest().toString();

        assertThat(s).contains(ID).doesNotContain(IBAN_1).doesNotContain(IBAN_2).doesNotContain("10.10");
    }
}
