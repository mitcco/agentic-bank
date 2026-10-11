package com.bank.fraud.web.servicedto;

import com.bank.fraud.web.dto.request.FraudCheckHttpRequestDto;
import com.bank.fraud.web.dto.response.FraudCheckHttpResponseDto;

public interface FraudCheckServiceDto {

    FraudCheckHttpResponseDto checkTransfer(FraudCheckHttpRequestDto request);
}
