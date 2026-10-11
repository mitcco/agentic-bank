package com.bank.fraud.application.exception;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;

public class InvalidFraudCheckRequestException extends InvalidFraudRequestException {

    public static final String MESSAGE = "Fraud check request is not valid";

    public InvalidFraudCheckRequestException(InvalidFraudRequestException cause) {
        super(MESSAGE);
        initCause(cause);
    }
}
