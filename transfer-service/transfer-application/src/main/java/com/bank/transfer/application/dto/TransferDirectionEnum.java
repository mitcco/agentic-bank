package com.bank.transfer.application.dto;

/** Direction of a transfer relative to the requested IBAN. ALL is only valid as a filter. */
public enum TransferDirectionEnum {
  ALL,
  OUTGOING,
  INCOMING
}
