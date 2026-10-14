package com.bank.transfer.postgres.repository.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "account")
public class AccountEntity {

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

  public AccountEntity(String id, String iban, BigDecimal balance, String currency) {
    this.id = id;
    this.iban = iban;
    this.balance = balance;
    this.currency = currency;
  }

  public String getId() {
    return id;
  }

  public String getIban() {
    return iban;
  }

  public BigDecimal getBalance() {
    return balance;
  }

  public String getCurrency() {
    return currency;
  }
}
