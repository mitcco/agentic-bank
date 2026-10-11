package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.DifferentCurrencyException;
import com.bank.fraud.domain.exception.InvalidFraudRequestException;

import java.math.BigDecimal;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {

    private static final int MAX_SCALE = 2;

    public Money {
        if (amount == null) {
            throw new InvalidFraudRequestException("Amount must not be null");
        }
        if (amount.signum() < 0) {
            throw new InvalidFraudRequestException("Amount must not be negative");
        }
        if (amount.scale() > MAX_SCALE) {
            throw new InvalidFraudRequestException("Amount must have at most " + MAX_SCALE + " decimals");
        }
        if (currency == null || currency.isBlank()) {
            throw new InvalidFraudRequestException("Currency must not be empty");
        }
    }

    public boolean isGreaterThan(Money other) {
        if (other == null) {
            throw new InvalidFraudRequestException("Amount to compare must not be null");
        }
        if (!currency.equals(other.currency)) {
            throw new DifferentCurrencyException("Currencies are different");
        }
        return amount.compareTo(other.amount) > 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Money m
                && amount.compareTo(m.amount) == 0
                && currency.equals(m.currency);
    }

    @Override
    public int hashCode() {
        BigDecimal normalized = amount.signum() == 0 ? BigDecimal.ZERO : amount.stripTrailingZeros();
        return Objects.hash(normalized, currency);
    }

    @Override
    public String toString() {
        return "Money[****]";
    }
}
