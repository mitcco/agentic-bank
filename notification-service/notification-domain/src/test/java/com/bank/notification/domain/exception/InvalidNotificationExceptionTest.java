package com.bank.notification.domain.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvalidNotificationExceptionTest {

  @Test
  void isRuntimeExceptionButNotIllegalArgumentException() {
    assertThat(RuntimeException.class.isAssignableFrom(InvalidNotificationException.class))
        .isTrue();
    assertThat(IllegalArgumentException.class.isAssignableFrom(InvalidNotificationException.class))
        .isFalse();
  }

  @Test
  void keepsTheGivenMessage() {
    assertThat(new InvalidNotificationException("x")).hasMessage("x");
  }
}
