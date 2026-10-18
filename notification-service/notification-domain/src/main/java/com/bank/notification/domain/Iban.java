package com.bank.notification.domain;

import com.bank.notification.domain.exception.InvalidNotificationException;

import java.util.Locale;
import java.util.regex.Pattern;

public record Iban(String value) {

  private static final int LENGTH = 27;
  private static final Pattern FORMAT = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]+");

  public Iban {
    if (value == null || value.isBlank()) {
      throw new InvalidNotificationException("IBAN must not be null or blank");
    }
    value = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    if (value.length() != LENGTH) {
      throw new InvalidNotificationException("IBAN must be " + LENGTH + " characters long");
    }
    if (!FORMAT.matcher(value).matches()) {
      throw new InvalidNotificationException("IBAN has an invalid format");
    }
  }

  @Override
  public String toString() {
    return "Iban[****]";
  }
}
