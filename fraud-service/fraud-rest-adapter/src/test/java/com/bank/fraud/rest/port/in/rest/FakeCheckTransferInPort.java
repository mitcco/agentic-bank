package com.bank.fraud.rest.port.in.rest;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.dto.FraudCheckResponseDto;
import com.bank.fraud.application.port.in.CheckTransferInPort;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake scritto a mano: registra le richieste ricevute e puo' rispondere con una
 * risposta configurata oppure lanciare un'eccezione. Di default risponde ALLOWED
 * con il transferId ricevuto.
 */
class FakeCheckTransferInPort implements CheckTransferInPort {

    private final List<FraudCheckRequestDto> requests = new ArrayList<>();
    private FraudCheckResponseDto response;
    private RuntimeException toThrow;

    FakeCheckTransferInPort responding(FraudCheckResponseDto response) {
        this.response = response;
        return this;
    }

    FakeCheckTransferInPort throwing(RuntimeException e) {
        this.toThrow = e;
        return this;
    }

    List<FraudCheckRequestDto> requests() {
        return requests;
    }

    @Override
    public FraudCheckResponseDto checkTransfer(FraudCheckRequestDto request) {
        requests.add(request);
        if (toThrow != null) {
            throw toThrow;
        }
        if (response != null) {
            return response;
        }
        return new FraudCheckResponseDto(request.transferId(), "ALLOWED", List.of());
    }
}
