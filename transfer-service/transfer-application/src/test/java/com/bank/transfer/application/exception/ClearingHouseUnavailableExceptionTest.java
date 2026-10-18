package com.bank.transfer.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClearingHouseUnavailableExceptionTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";

  @Test
  void messageHasTheTransferIdButNoCause() {
    ClearingHouseUnavailableException exception = new ClearingHouseUnavailableException(TRANSFER_ID);

    assertThat(exception.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(exception).hasMessage("Clearing house unavailable for transfer " + TRANSFER_ID);
    assertThat(exception.getCause()).isNull();
  }

  @Test
  void isARuntimeExceptionButNotAnIllegalArgument() {
    ClearingHouseUnavailableException exception = new ClearingHouseUnavailableException(TRANSFER_ID);

    assertThat(exception).isInstanceOf(RuntimeException.class);
    assertThat(exception).isNotInstanceOf(IllegalArgumentException.class);
  }
}
