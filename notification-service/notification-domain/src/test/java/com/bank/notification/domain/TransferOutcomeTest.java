package com.bank.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransferOutcomeTest {

  @Test
  void hasExactlyCompletedAndRejected() {
    assertThat(TransferOutcome.values())
        .containsExactly(TransferOutcome.COMPLETED, TransferOutcome.REJECTED);
  }
}
