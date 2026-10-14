package com.bank.transfer.application.exception;

/** An outbox row that cannot be read as a notification. Carries only the row id: no payload, no transfer id. */
public class InvalidOutboxRowException extends RuntimeException {

  private final long rowId;

  public InvalidOutboxRowException(long rowId) {
    super("Invalid outbox row: id=" + rowId);
    this.rowId = rowId;
  }

  public long rowId() {
    return rowId;
  }
}
