package com.bank.transfer.application.exception;

public class TransferNotFoundException extends RuntimeException {

  public TransferNotFoundException() {
    super("Transfer not found");
  }
}
