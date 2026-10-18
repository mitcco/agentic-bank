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

  private static Transfer transferIn(TransferStatusEnum status) {
    return new Transfer("t-1", SOURCE, DEST, EUR("10.50"), status);
  }

  @Test
  void markSentMovesAPendingTransferToSentKeepingIdAndMoney() {
    Transfer pending = Transfer.create(SOURCE, DEST, EUR("100.25"));

    Transfer sent = pending.markSent();

    assertThat(sent.status()).isEqualTo(TransferStatusEnum.SENT);
    assertThat(sent.id()).isEqualTo(pending.id());
    assertThat(sent.source()).isEqualTo(pending.source());
    assertThat(sent.dest()).isEqualTo(pending.dest());
    assertThat(sent.money()).isEqualTo(pending.money());
    assertThat(pending.status()).isEqualTo(TransferStatusEnum.PENDING);
  }

  @Test
  void settleMovesASentTransferToCompleted() {
    Transfer sent = transferIn(TransferStatusEnum.SENT);

    Transfer completed = sent.settle();

    assertThat(completed.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(completed.id()).isEqualTo(sent.id());
    assertThat(completed.money()).isEqualTo(sent.money());
    assertThat(sent.status()).isEqualTo(TransferStatusEnum.SENT);
  }

  @Test
  void rejectAfterSentMovesASentTransferToRejected() {
    Transfer sent = transferIn(TransferStatusEnum.SENT);

    Transfer rejected = sent.rejectAfterSent();

    assertThat(rejected.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(rejected.id()).isEqualTo(sent.id());
    assertThat(rejected.money()).isEqualTo(sent.money());
    assertThat(sent.status()).isEqualTo(TransferStatusEnum.SENT);
  }

  @Test
  void pendingTransferCannotBeSettledOrRejectedAfterSent() {
    Transfer pending = transferIn(TransferStatusEnum.PENDING);

    assertThatThrownBy(pending::settle).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(pending::rejectAfterSent).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void sentTransferCannotBeSentAgainNorCompletedNorRejectedAsPending() {
    Transfer sent = transferIn(TransferStatusEnum.SENT);

    assertThatThrownBy(sent::markSent).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(sent::complete).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(sent::reject).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void completedTransferCannotBeSentSettledOrRejectedAfterSent() {
    Transfer completed = transferIn(TransferStatusEnum.COMPLETED);

    assertThatThrownBy(completed::markSent).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(completed::settle).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(completed::rejectAfterSent).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectedTransferCannotBeSentSettledOrRejectedAfterSent() {
    Transfer rejected = transferIn(TransferStatusEnum.REJECTED);

    assertThatThrownBy(rejected::markSent).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(rejected::settle).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(rejected::rejectAfterSent).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void invalidTransitionMessageNamesTheCurrentStatus() {
    Transfer sent = transferIn(TransferStatusEnum.SENT);

    assertThatThrownBy(sent::markSent)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Transfer is already SENT");
  }

  @Test
  void markUnknownMovesASentTransferToUnknownKeepingIdAndMoney() {
    Transfer sent = Transfer.create(SOURCE, DEST, EUR("100.25")).markSent();

    Transfer unknown = sent.markUnknown();

    assertThat(unknown.status()).isEqualTo(TransferStatusEnum.UNKNOWN);
    assertThat(unknown.id()).isEqualTo(sent.id());
    assertThat(unknown.source()).isEqualTo(sent.source());
    assertThat(unknown.dest()).isEqualTo(sent.dest());
    assertThat(unknown.money()).isEqualTo(sent.money());
    assertThat(sent.status()).isEqualTo(TransferStatusEnum.SENT);
  }

  @Test
  void settleCompletesAnUnknownTransfer() {
    Transfer unknown = transferIn(TransferStatusEnum.UNKNOWN);

    Transfer completed = unknown.settle();

    assertThat(completed.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(completed.id()).isEqualTo(unknown.id());
    assertThat(completed.money()).isEqualTo(unknown.money());
    assertThat(unknown.status()).isEqualTo(TransferStatusEnum.UNKNOWN);
  }

  @Test
  void rejectAfterSentRejectsAnUnknownTransfer() {
    Transfer unknown = transferIn(TransferStatusEnum.UNKNOWN);

    Transfer rejected = unknown.rejectAfterSent();

    assertThat(rejected.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(rejected.id()).isEqualTo(unknown.id());
    assertThat(rejected.money()).isEqualTo(unknown.money());
    assertThat(unknown.status()).isEqualTo(TransferStatusEnum.UNKNOWN);
  }

  @Test
  void onlyASentTransferCanBecomeUnknown() {
    for (TransferStatusEnum status : new TransferStatusEnum[] {
      TransferStatusEnum.PENDING, TransferStatusEnum.UNKNOWN, TransferStatusEnum.COMPLETED,
      TransferStatusEnum.REJECTED}) {
      Transfer transfer = transferIn(status);

      assertThatThrownBy(transfer::markUnknown)
        .as("markUnknown from " + status)
        .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void unknownTransferCannotBeSentNorCompletedNorRejectedAsPending() {
    Transfer unknown = transferIn(TransferStatusEnum.UNKNOWN);

    assertThatThrownBy(unknown::markSent).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(unknown::complete).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(unknown::reject).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void invalidMarkUnknownMessageNamesTheCurrentStatus() {
    Transfer completed = transferIn(TransferStatusEnum.COMPLETED);

    assertThatThrownBy(completed::markUnknown)
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("Transfer is already COMPLETED");
  }
}
