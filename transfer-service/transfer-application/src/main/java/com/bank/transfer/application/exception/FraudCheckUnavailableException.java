package com.bank.transfer.application.exception;

/**
 * The fraud check could not give a valid answer (fail closed). Deliberately without cause and
 * not an IllegalArgumentException (which the web layer maps to 400).
 */
public class FraudCheckUnavailableException extends RuntimeException {

  private final String transferId;

  public FraudCheckUnavailableException(String transferId) {
    super("Fraud check unavailable for transfer " + transferId);
    this.transferId = transferId;
  }

  public String transferId() {
    return transferId;
  }
}
