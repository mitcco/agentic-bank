package com.bank.transfer.domain;

/**
 * Bank code (ABI): exactly five uppercase letters or digits. No normalization is applied.
 */
public record BankCode(String value) {

  public BankCode {
    if (value == null || !value.matches("[A-Z0-9]{5}")) {
      throw new IllegalArgumentException("Malformed bank code, it has to be 5 uppercase letters or digits");
    }
  }
}
