package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;
import com.bank.fraud.domain.exception.UnsupportedCurrencyException;

public record TransferToAssess(String transferId, Iban source, Iban destination, Money amount) {

    public static final String SUPPORTED_CURRENCY = "EUR";

    public TransferToAssess {
        if (transferId == null || transferId.isBlank()) {
            throw new InvalidFraudRequestException("Transfer id must not be empty");
        }
        if (source == null) {
            throw new InvalidFraudRequestException("Source IBAN must not be null");
        }
        if (destination == null) {
            throw new InvalidFraudRequestException("Destination IBAN must not be null");
        }
        if (amount == null) {
            throw new InvalidFraudRequestException("Amount must not be null");
        }
        if (amount.amount().signum() <= 0) {
            throw new InvalidFraudRequestException("Amount must be greater than 0");
        }
        if (!SUPPORTED_CURRENCY.equals(amount.currency())) {
            throw new UnsupportedCurrencyException("Only EUR is supported");
        }
    }

    @Override
    public String toString() {
        return "TransferToAssess[transferId=" + transferId + "]";
    }
}
