package com.bank.fraud.application.port.in;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.application.dto.FraudCheckResponseDto;

public interface CheckTransferInPort {

    FraudCheckResponseDto checkTransfer(FraudCheckRequestDto request);
}
