package com.bank.transfer.clearing.port.out.clearing.impl;

public enum SimulatedClearingModeEnum {
  ACCEPT,
  REJECT,
  UNAVAILABLE,
  TIMEOUT_THEN_ACCEPT,
  TIMEOUT_THEN_REJECT
}
