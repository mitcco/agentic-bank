package com.bank.transfer.postgres.repository.model;

import com.bank.transfer.domain.TransferStatusEnum;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transfer")
public class TransferEntity {

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

  /** Written by the database default only, in the time zone of the JDBC session (the JVM one). */
  @Column(name = "created_at", insertable = false, updatable = false)
  private LocalDateTime createdAt;

  protected TransferEntity() {
  }

  public TransferEntity(String id, String sourceIban, String destIban, BigDecimal amount, String currency,
    TransferStatusEnum status) {
    this.id = id;
    this.sourceIban = sourceIban;
    this.destIban = destIban;
    this.amount = amount;
    this.currency = currency;
    this.status = status;
  }

  public String getId() {
    return id;
  }

  public String getSourceIban() {
    return sourceIban;
  }

  public String getDestIban() {
    return destIban;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public TransferStatusEnum getStatus() {
    return status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
