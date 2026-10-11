package com.bank.fraud.domain.exception;

public class UnsupportedCurrencyException extends RuntimeException {

    public UnsupportedCurrencyException(String message) {
        super(message);
    }
}
