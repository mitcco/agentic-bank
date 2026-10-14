package com.bank.fraud.rest.servicedto.internal;

import com.bank.fraud.application.port.in.CheckTransferInPort;
import com.bank.fraud.rest.dto.request.FraudCheckHttpRequestDto;
import com.bank.fraud.rest.dto.response.FraudCheckHttpResponseDto;
import com.bank.fraud.rest.mapper.FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapper;
import com.bank.fraud.rest.mapper.FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapper;
import com.bank.fraud.rest.servicedto.FraudCheckServiceDto;
import org.springframework.stereotype.Service;

@Service
public class FraudCheckServiceDtoImpl implements FraudCheckServiceDto {

    private final CheckTransferInPort checkTransferInPort;
    private final FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapper requestMapper;
    private final FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapper responseMapper;

    public FraudCheckServiceDtoImpl(CheckTransferInPort checkTransferInPort,
            FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapper requestMapper,
            FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapper responseMapper) {
        this.checkTransferInPort = checkTransferInPort;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
    }

    @Override
    public FraudCheckHttpResponseDto checkTransfer(FraudCheckHttpRequestDto request) {
        return responseMapper.fraudCheckResponseDtoToFraudCheckHttpResponseDto(
                checkTransferInPort.checkTransfer(
                        requestMapper.fraudCheckHttpRequestDtoToFraudCheckRequestDto(request)));
    }
}
