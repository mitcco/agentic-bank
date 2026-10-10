package com.bank.transfer.application.port.out;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import java.math.BigDecimal;

public interface FraudCheckOutPort {
  FraudCheckResultDto checkFraud(String sourceIban, String destIban, BigDecimal amount, String currency);
}
