package com.bank.transfer.domain;

import io.micrometer.common.util.StringUtils;

public final class Iban {

  private final String value;

  public Iban(String value) {
    if (!StringUtils.isBlank(value)) {
      throw new IllegalArgumentException("Value not compliant");
    }
    String normalizedIban = value.strip().toUpperCase();
    if (normalizedIban.length() != 27) {
      throw new IllegalArgumentException("Malformed IBAN, it has to be 27 characters");
    }

    this.value = normalizedIban;
  }

  public String value() {
    return value;
  }

}