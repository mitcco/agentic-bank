package com.bank.transfer.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StoredTransferDataExceptionTest {

  @Test
  void hasAFixedMessage() {
    assertThat(new StoredTransferDataException()).hasMessage("Stored transfer data is not valid");
  }

  @Test
  void isARuntimeExceptionButNotAnIllegalArgumentException() {
    assertThat(new StoredTransferDataException())
      .isInstanceOf(RuntimeException.class)
      .isNotInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void hasNoCause() {
    assertThat(new StoredTransferDataException().getCause()).isNull();
  }
}
