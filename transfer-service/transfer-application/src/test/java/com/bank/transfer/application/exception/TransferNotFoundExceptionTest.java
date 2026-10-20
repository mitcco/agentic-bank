package com.bank.transfer.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransferNotFoundExceptionTest {

  @Test
  void hasAFixedMessage() {
    assertThat(new TransferNotFoundException()).hasMessage("Transfer not found");
  }

  @Test
  void isNotAnIllegalArgumentException() {
    assertThat(new TransferNotFoundException())
      .isInstanceOf(RuntimeException.class)
      .isNotInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hasNoCause() {
    assertThat(new TransferNotFoundException().getCause()).isNull();
  }
}
