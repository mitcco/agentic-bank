package com.bank.transfer.postgres.repository.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox")
public class OutboxEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "transfer_id", nullable = false, length = 36)
  private String transferId;

  @JdbcTypeCode(SqlTypes.LONGVARCHAR)
  @Column(nullable = false, columnDefinition = "TEXT")
  private String payload;

  protected OutboxEntity() {
  }

  public OutboxEntity(String transferId, String payload) {
    this.transferId = transferId;
    this.payload = payload;
  }

  public Long getId() {
    return id;
  }

  public String getTransferId() {
    return transferId;
  }

  public String getPayload() {
    return payload;
  }
}
