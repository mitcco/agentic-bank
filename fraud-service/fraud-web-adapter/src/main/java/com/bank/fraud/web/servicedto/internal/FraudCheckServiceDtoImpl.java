package com.bank.fraud.web.servicedto.internal;

import com.bank.fraud.application.port.in.CheckTransferInPort;
import com.bank.fraud.web.dto.request.FraudCheckHttpRequestDto;
import com.bank.fraud.web.dto.response.FraudCheckHttpResponseDto;
import com.bank.fraud.web.mapper.FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapper;
import com.bank.fraud.web.mapper.FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapper;
import com.bank.fraud.web.servicedto.FraudCheckServiceDto;
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
