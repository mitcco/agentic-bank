package com.bank.transfer.domain;

import java.util.Locale;

public record Iban(String value) {

  private static final int LENGTH = 27;

  public Iban {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Value not compliant");
    }
    String normalized = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    if (normalized.length() != LENGTH) {
      throw new IllegalArgumentException("Malformed IBAN, it has to be " + LENGTH + " characters");
    }
    if (!normalized.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]+")) {
      throw new IllegalArgumentException("Malformed IBAN, invalid characters");
    }
    value = normalized;
  }
}
