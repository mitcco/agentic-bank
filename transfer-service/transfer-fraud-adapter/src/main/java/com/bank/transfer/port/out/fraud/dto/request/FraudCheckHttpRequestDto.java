package com.bank.transfer.port.out.fraud.dto.request;

import java.math.BigDecimal;

public record FraudCheckHttpRequestDto(String transferId, String sourceIban, String destinationIban,
                                       BigDecimal amount, String currency) {

  /** A negative scale (e.g. 1E+3) would be written with an exponent: it is brought to scale 0. */
  public static FraudCheckHttpRequestDto of(String transferId, String sourceIban, String destinationIban,
    BigDecimal amount, String currency) {
    BigDecimal normalized = amount.scale() < 0 ? amount.setScale(0) : amount;
    return new FraudCheckHttpRequestDto(transferId, sourceIban, destinationIban, normalized, currency);
  }

  @Override
  public String toString() {
    return "FraudCheckHttpRequestDto[transferId=" + transferId + "]";
  }
}
