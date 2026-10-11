package com.bank.transfer.port.out.fraud.impl;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class StubFraudCheck implements FraudCheckOutPort {

  private static final String EXCEEDS_THRESHOLD = "Amount must not exceed %s EUR";
  private static final String SOURCE_LOCKED = "Source IBAN is locked";
  private static final String DEST_LOCKED = "Destination IBAN is locked";

  private final BigDecimal threshold;
  private final Set<Iban> blockedIbans;

  public StubFraudCheck(BigDecimal threshold, Set<Iban> blockedIbans) {
    this.threshold = threshold;
    this.blockedIbans = blockedIbans;
  }

  /*
  transferId and currency are ignored in stub
   */
  @Override
  public FraudCheckResultDto checkFraud(String transferId, String sourceIban, String destIban, BigDecimal amount,
    String currency) {
    List<String> reasons = new ArrayList<>();

    if (amount.compareTo(threshold) > 0) {
      reasons.add(String.format(EXCEEDS_THRESHOLD, threshold.stripTrailingZeros().toPlainString()));
    }
    if (blockedIbans.contains(new Iban(sourceIban))) {
      reasons.add(SOURCE_LOCKED);
    }
    if (blockedIbans.contains(new Iban(destIban))) {
      reasons.add(DEST_LOCKED);
    }

    if (reasons.isEmpty()) {
      return new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
    }
    return new FraudCheckResultDto(FraudStatusEnum.REJECTED, reasons);
  }
}
