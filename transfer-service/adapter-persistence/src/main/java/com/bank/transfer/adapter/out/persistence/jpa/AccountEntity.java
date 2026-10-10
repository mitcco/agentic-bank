package com.bank.transfer.adapter.out.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "account")
class AccountEntity {

  @Id
  private String id;

  @Column(nullable = false, unique = true, length = 27)
  private String iban;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal balance;

  @Column(nullable = false, length = 3)
  private String currency;

  protected AccountEntity() {
  }

  AccountEntity(String id, String iban, BigDecimal balance, String currency) {
    this.id = id;
    this.iban = iban;
    this.balance = balance;
    this.currency = currency;
  }

  String getId() {
    return id;
  }

  String getIban() {
    return iban;
  }

  BigDecimal getBalance() {
    return balance;
  }

  String getCurrency() {
    return currency;
  }
}
