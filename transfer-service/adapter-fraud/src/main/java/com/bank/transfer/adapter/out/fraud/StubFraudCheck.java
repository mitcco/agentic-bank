package com.bank.transfer.adapter.out.fraud;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.FraudDecisionEnum;
import com.bank.transfer.domain.Iban;
import java.math.BigDecimal;
import java.util.Set;

public class StubFraudCheck implements FraudCheckOutPort {

  private static final String EXCEEDS_THRESHOLD = "Amount must not exceed %s";
  private static final String SOURCE_BLOCKED = "Source IBAN is blocked";
  private static final String DEST_BLOCKED = "Destination IBAN is blocked";

  private final BigDecimal threshold;
  private final Set<Iban> blockedIbans;

  public StubFraudCheck(BigDecimal threshold, Set<Iban> blockedIbans) {
    this.threshold = threshold;
    this.blockedIbans = blockedIbans;
  }

  /*
  currency is ignored in stub
   */
  @Override
  public FraudCheckResultDto checkFraud(String sourceIban, String destIban, BigDecimal amount, String currency) {
    if (amount.compareTo(threshold) > 0)  {
      return new FraudCheckResultDto(FraudDecisionEnum.REJECTED, String.format(EXCEEDS_THRESHOLD, threshold));
    }
    Iban source = new Iban(sourceIban);
    Iban dest = new Iban(destIban);

    if (blockedIbans.contains(source)) {
      return new FraudCheckResultDto(FraudDecisionEnum.REJECTED, SOURCE_BLOCKED);
    }
    if (blockedIbans.contains(dest)) {
      return new FraudCheckResultDto(FraudDecisionEnum.REJECTED, DEST_BLOCKED);
    }

    return new FraudCheckResultDto(FraudDecisionEnum.ALLOWED, "");
  }
}
