package com.bank.transfer.application.exception;

/**
 * The clearing house did not confirm the instruction: it may or may not have been received.
 * Deliberately not a ClearingHouseUnavailableException (that one means "certainly not received" and
 * triggers the compensation), without cause and not an IllegalArgumentException.
 */
public class ClearingHouseTimeoutException extends RuntimeException {

  private final String transferId;

  public ClearingHouseTimeoutException(String transferId) {
    super("Clearing house did not confirm the instruction for transfer " + transferId);
    this.transferId = transferId;
  }

  public String transferId() {
    return transferId;
  }
}
