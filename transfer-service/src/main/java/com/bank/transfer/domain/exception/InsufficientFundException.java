package com.bank.transfer.domain.exception;

public class InsufficientFundException extends RuntimeException {

  public InsufficientFundException() {
    super("Insufficient Fund on your account");
  }
}
