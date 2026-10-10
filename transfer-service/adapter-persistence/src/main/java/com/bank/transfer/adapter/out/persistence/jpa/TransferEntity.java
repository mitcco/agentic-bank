package com.bank.transfer.adapter.out.persistence.jpa;

import com.bank.transfer.domain.TransferStatusEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "transfer")
class TransferEntity {

  @Id
  private String id;

  @Column(nullable = false, length = 27)
  private String sourceIban;

  @Column(nullable = false, length = 27)
  private String destIban;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private TransferStatusEnum status;

  protected TransferEntity() {
  }

  TransferEntity(String id, String sourceIban, String destIban, BigDecimal amount, String currency,
    TransferStatusEnum status) {
    this.id = id;
    this.sourceIban = sourceIban;
    this.destIban = destIban;
    this.amount = amount;
    this.currency = currency;
    this.status = status;
  }

  String getId() {
    return id;
  }

  String getSourceIban() {
    return sourceIban;
  }

  String getDestIban() {
    return destIban;
  }

  BigDecimal getAmount() {
    return amount;
  }

  String getCurrency() {
    return currency;
  }

  TransferStatusEnum getStatus() {
    return status;
  }
}
