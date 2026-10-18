package com.bank.transfer.application.exception;

/**
 * The clearing house could not take the instruction. Deliberately without cause and not an
 * IllegalArgumentException (which the web layer maps to 400).
 */
public class ClearingHouseUnavailableException extends RuntimeException {

  private final String transferId;

  public ClearingHouseUnavailableException(String transferId) {
    super("Clearing house unavailable for transfer " + transferId);
    this.transferId = transferId;
  }

  public String transferId() {
    return transferId;
  }
}
