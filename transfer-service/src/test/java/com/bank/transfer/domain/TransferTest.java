package com.bank.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TransferTest {

  private static final Iban SOURCE = new Iban("IT60X0542811101000000123456");
  private static final Iban DEST = new Iban("IT60X0542811101000000654321");

  private static Money EUR(String amount) {
    return new Money(new BigDecimal(amount), "EUR");
  }

  @Test
  void createsValidTransfer() {
    Transfer transfer = new Transfer("t-1", SOURCE, DEST, EUR("10"), TransferStatusEnum.PENDING);

    assertThat(transfer.source()).isEqualTo(SOURCE);
    assertThat(transfer.dest()).isEqualTo(DEST);
    assertThat(transfer.money()).isEqualTo(EUR("10"));
    assertThat(transfer.status()).isEqualTo(TransferStatusEnum.PENDING);
  }

  @Test
  void rejectsZeroAmount() {
    assertThatThrownBy(() -> new Transfer("t-1", SOURCE, DEST, EUR("0"), TransferStatusEnum.PENDING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsSameSourceAndDestEvenIfFormattedDifferently() {
    Iban sameButSpaced = new Iban("it60 x054 2811 1010 0000 0123 456");

    assertThatThrownBy(
        () -> new Transfer("t-1", SOURCE, sameButSpaced, EUR("10"), TransferStatusEnum.PENDING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullFields() {
    assertThatThrownBy(() -> new Transfer("t-1", null, DEST, EUR("10"), TransferStatusEnum.PENDING))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Transfer("t-1", SOURCE, DEST, null, TransferStatusEnum.PENDING))
        .isInstanceOf(NullPointerException.class);
  }
}
