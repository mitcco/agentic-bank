package com.bank.transfer.application.port.out;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import java.math.BigDecimal;

public interface FraudCheckOutPort {

  /**
   * @throws com.bank.transfer.application.exception.FraudCheckUnavailableException for every outcome
   *   other than a valid ALLOWED or REJECTED answer
   */
  FraudCheckResultDto checkFraud(String transferId, String sourceIban, String destIban, BigDecimal amount,
    String currency);
}
