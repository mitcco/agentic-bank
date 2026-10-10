package com.bank.transfer.domain.exception;

public class DifferentCurrencyException extends RuntimeException {

  public DifferentCurrencyException(String message) {
    super(message);
  }
}
