package com.bank.fraud.rest.port.in.rest;

import com.bank.fraud.rest.dto.request.FraudCheckHttpRequestDto;
import com.bank.fraud.rest.dto.response.FraudCheckHttpResponseDto;
import com.bank.fraud.rest.servicedto.FraudCheckServiceDto;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FraudCheckController {

    private final FraudCheckServiceDto fraudCheckServiceDto;

    public FraudCheckController(FraudCheckServiceDto fraudCheckServiceDto) {
        this.fraudCheckServiceDto = fraudCheckServiceDto;
    }

    @PostMapping(path = "/fraud-check",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FraudCheckHttpResponseDto checkTransfer(@Valid @RequestBody FraudCheckHttpRequestDto request) {
        return fraudCheckServiceDto.checkTransfer(request);
    }
}
