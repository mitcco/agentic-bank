package com.bank.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  private static final String TOO_MANY_DECIMALS = "Transfer amount can have at most 2 decimals";

  @ParameterizedTest
  @ValueSource(strings = {"1.234", "0.001", "100.255"})
  void rejectsAmountsWithMoreThanTwoSignificantDecimals(String amount) {
    assertThatThrownBy(() -> new Transfer("t-1", SOURCE, DEST, EUR(amount), TransferStatusEnum.PENDING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TOO_MANY_DECIMALS);
  }

  @Test
  void createRejectsAmountsWithMoreThanTwoSignificantDecimals() {
    assertThatThrownBy(() -> Transfer.create(SOURCE, DEST, EUR("1.234")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(TOO_MANY_DECIMALS);
  }

  @Test
  void tooManyDecimalsMessageDoesNotRevealTheAmount() {
    assertThatThrownBy(() -> new Transfer("t-1", SOURCE, DEST, EUR("1.234"), TransferStatusEnum.PENDING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("1.234");
  }

  @ParameterizedTest
  @ValueSource(strings = {"100", "100.2", "100.25", "10.5000", "100.00", "2E+4", "1E+2"})
  void acceptsAmountsWithAtMostTwoSignificantDecimals(String amount) {
    Money money = EUR(amount);

    Transfer transfer = new Transfer("t-1", SOURCE, DEST, money, TransferStatusEnum.PENDING);

    assertThat(transfer.money()).isEqualTo(money);
  }

  @Test
  void zeroWithManyDecimalsIsRefusedAsNotPositiveNotAsTooManyDecimals() {
    assertThatThrownBy(() -> new Transfer("t-1", SOURCE, DEST, EUR("0.000"), TransferStatusEnum.PENDING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Transfer amount has to be greater than 0");
  }

  @Test
  void tooManyDecimalsIsRefusedEvenWhenSourceAndDestAreTheSame() {
    assertThatThrownBy(() -> new Transfer("t-1", SOURCE, SOURCE, EUR("1.234"), TransferStatusEnum.PENDING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void completeKeepsIdAndMoneyOfAnAmountWithTwoDecimals() {
    Transfer pending = Transfer.create(SOURCE, DEST, EUR("100.25"));

    Transfer completed = pending.complete();

    assertThat(completed.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(completed.id()).isEqualTo(pending.id());
    assertThat(completed.money()).isEqualTo(pending.money());
  }

  @Test
  void rejectKeepsIdAndMoneyOfAnAmountWithTrailingZeros() {
    Transfer pending = Transfer.create(SOURCE, DEST, EUR("10.5000"));

    Transfer rejected = pending.reject();

    assertThat(rejected.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(rejected.id()).isEqualTo(pending.id());
    assertThat(rejected.money()).isEqualTo(pending.money());
  }

  @Test
  void completedTransferCannotBeCompletedOrRejectedAgain() {
    Transfer completed = Transfer.create(SOURCE, DEST, EUR("10")).complete();

    assertThatThrownBy(completed::complete).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(completed::reject).isInstanceOf(IllegalStateException.class);
  }
}
