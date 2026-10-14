package com.bank.fraud.rest.servicedto;

import com.bank.fraud.rest.dto.request.FraudCheckHttpRequestDto;
import com.bank.fraud.rest.dto.response.FraudCheckHttpResponseDto;

public interface FraudCheckServiceDto {

    FraudCheckHttpResponseDto checkTransfer(FraudCheckHttpRequestDto request);
}
