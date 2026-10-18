package com.bank.transfer.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ClearingHouseTimeoutExceptionTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";

  @Test
  void messageHasTheTransferIdButNoCause() {
    ClearingHouseTimeoutException exception = new ClearingHouseTimeoutException(TRANSFER_ID);

    assertThat(exception.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(exception).hasMessage("Clearing house did not confirm the instruction for transfer " + TRANSFER_ID);
    assertThat(exception.getCause()).isNull();
  }

  /**
   * Not a subclass of the "unavailable" exception: that one means "certainly not received" and is compensated at
   * once, a timeout means "maybe received" and must never be compensated.
   */
  @Test
  void isNeitherAnUnavailableNorAnIllegalArgumentException() {
    ClearingHouseTimeoutException exception = new ClearingHouseTimeoutException(TRANSFER_ID);

    assertThat(exception).isInstanceOf(RuntimeException.class);
    assertThat(exception).isNotInstanceOf(ClearingHouseUnavailableException.class);
    assertThat(exception).isNotInstanceOf(IllegalArgumentException.class);
  }
}
