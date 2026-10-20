package com.bank.transfer.application.exception;

/**
 * Thrown when the stored data of a transfer cannot be turned into a valid response.
 * The message is fixed and there is no cause, because both could carry IBANs or amounts.
 */
public class StoredTransferDataException extends RuntimeException {

  public StoredTransferDataException() {
    super("Stored transfer data is not valid");
  }
}
