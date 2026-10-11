package com.bank.fraud.domain.exception;

public class InvalidFraudRequestException extends RuntimeException {

    public InvalidFraudRequestException(String message) {
        super(message);
    }
}
