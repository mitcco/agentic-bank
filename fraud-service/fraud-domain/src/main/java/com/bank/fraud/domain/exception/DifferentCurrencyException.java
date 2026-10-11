package com.bank.fraud.domain.exception;

public class DifferentCurrencyException extends RuntimeException {

    public DifferentCurrencyException(String message) {
        super(message);
    }
}
