package com.bank.fraud.domain;

import com.bank.fraud.domain.exception.InvalidFraudRequestException;

import java.util.Locale;
import java.util.regex.Pattern;

public record Iban(String value) {

    private static final int LENGTH = 27;
    private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]+");

    public Iban {
        if (value == null || value.isBlank()) {
            throw new InvalidFraudRequestException("IBAN must not be empty");
        }
        value = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (value.length() != LENGTH) {
            throw new InvalidFraudRequestException("IBAN must be " + LENGTH + " characters long");
        }
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidFraudRequestException("IBAN format is not valid");
        }
    }

    @Override
    public String toString() {
        return "Iban[****]";
    }
}
