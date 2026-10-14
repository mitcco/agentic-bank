package com.bank.notification.mongo.repository.model;

import org.bson.types.Decimal128;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;
import java.util.List;

/**
 * MongoDB document of a transfer notification. The only class holding Spring Data annotations.
 *
 * <p>It has no {@code @Version} and does not implement {@code Persistable}: with a non-null
 * String id Spring Data then performs a replace with upsert, which makes the save idempotent by
 * {@code transferId}. Adding either would turn the second save into an insert and fail with a
 * duplicate key error.
 */
@Document(collection = "transfer_notifications")
public final class TransferNotificationDocument {

  @Id
  private final String transferId;
  private final String sourceIban;
  private final String destinationIban;
  private final Decimal128 amount;
  private final String currency;
  private final String status;
  private final List<String> reasons;
  private final Date occurredAt;

  public TransferNotificationDocument(String transferId, String sourceIban,
      String destinationIban, Decimal128 amount, String currency, String status,
      List<String> reasons, Date occurredAt) {
    this.transferId = transferId;
    this.sourceIban = sourceIban;
    this.destinationIban = destinationIban;
    this.amount = amount;
    this.currency = currency;
    this.status = status;
    this.reasons = List.copyOf(reasons);
    this.occurredAt = new Date(occurredAt.getTime());
  }

  public String getTransferId() {
    return transferId;
  }

  public String getSourceIban() {
    return sourceIban;
  }

  public String getDestinationIban() {
    return destinationIban;
  }

  public Decimal128 getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getStatus() {
    return status;
  }

  public List<String> getReasons() {
    return reasons;
  }

  public Date getOccurredAt() {
    return new Date(occurredAt.getTime());
  }

  @Override
  public String toString() {
    return "TransferNotificationDocument[transferId=" + transferId
        + ", amount=" + amount
        + ", currency=" + currency
        + ", status=" + status
        + ", reasons=" + reasons
        + ", occurredAt=" + occurredAt + "]";
  }
}
