package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferNotificationDtoTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0542811101000000000002";
  private static final BigDecimal AMOUNT = new BigDecimal("100.25");
  private static final String CURRENCY = "EUR";
  private static final String LOCKED = "Source IBAN is locked";

  private static TransferNotificationDto notification(TransferStatusEnum status, List<String> reasons) {
    return new TransferNotificationDto(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, CURRENCY, status, reasons);
  }

  private static TransferNotificationDto notificationWithStatus(
    String transferId, String sourceIban, String destIban, BigDecimal amount, String currency, TransferStatusEnum status) {
    return new TransferNotificationDto(transferId, sourceIban, destIban, amount, currency, status, List.of());
  }

  private static TransferNotificationDto withAmount(BigDecimal amount) {
    return notificationWithStatus(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, amount, CURRENCY, TransferStatusEnum.COMPLETED);
  }

  // 1
  @Test
  void createsCompletedNotificationWithoutReasons() {
    TransferNotificationDto dto = notification(TransferStatusEnum.COMPLETED, List.of());

    assertThat(dto.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(dto.sourceIban()).isEqualTo(SOURCE_IBAN);
    assertThat(dto.destIban()).isEqualTo(DEST_IBAN);
    assertThat(dto.amount()).isEqualByComparingTo(AMOUNT);
    assertThat(dto.currency()).isEqualTo(CURRENCY);
    assertThat(dto.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(dto.reasons()).isEmpty();
  }

  // 2
  @Test
  void createsRejectedNotificationWithReasons() {
    TransferNotificationDto dto = notification(TransferStatusEnum.REJECTED, List.of(LOCKED));

    assertThat(dto.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(dto.reasons()).containsExactly(LOCKED);
  }

  // 3
  @Test
  void nullReasonsBecomeAnEmptyListForCompleted() {
    TransferNotificationDto dto = notification(TransferStatusEnum.COMPLETED, null);

    assertThat(dto.reasons()).isNotNull().isEmpty();
  }

  // 4a
  @Test
  void rejectedWithNullReasonsIsRefused() {
    assertThatThrownBy(() -> notification(TransferStatusEnum.REJECTED, null))
      .isInstanceOf(IllegalArgumentException.class);
  }

  // 4b
  @Test
  void rejectedWithEmptyReasonsIsRefused() {
    assertThatThrownBy(() -> notification(TransferStatusEnum.REJECTED, List.of()))
      .isInstanceOf(IllegalArgumentException.class);
  }

  // 5
  @Test
  void completedWithReasonsIsRefused() {
    assertThatThrownBy(() -> notification(TransferStatusEnum.COMPLETED, List.of("x")))
      .isInstanceOf(IllegalArgumentException.class);
  }

  // 6
  @Test
  void pendingStatusIsRefused() {
    assertThatThrownBy(() -> notification(TransferStatusEnum.PENDING, List.of()))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sentStatusIsRefused() {
    assertThatThrownBy(() -> notification(TransferStatusEnum.SENT, List.of()))
      .isInstanceOf(IllegalArgumentException.class);
  }

  // 7a-7f
  @Test
  void nullTransferIdIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(null, SOURCE_IBAN, DEST_IBAN, AMOUNT, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("transferId");
  }

  @Test
  void nullSourceIbanIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, null, DEST_IBAN, AMOUNT, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("sourceIban");
  }

  @Test
  void nullDestIbanIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, SOURCE_IBAN, null, AMOUNT, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("destIban");
  }

  @Test
  void nullAmountIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, null, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("amount");
  }

  @Test
  void nullCurrencyIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, null, TransferStatusEnum.COMPLETED))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("currency");
  }

  @Test
  void nullStatusIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, CURRENCY, null))
      .isInstanceOf(NullPointerException.class)
      .hasMessage("status");
  }

  // 8a
  @Test
  void zeroAmountIsRefused() {
    assertThatThrownBy(() -> withAmount(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
  }

  // 8b
  @Test
  void zeroAmountWithScaleIsRefused() {
    assertThatThrownBy(() -> withAmount(new BigDecimal("0.00"))).isInstanceOf(IllegalArgumentException.class);
  }

  // 8c
  @Test
  void negativeAmountIsRefused() {
    assertThatThrownBy(() -> withAmount(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
  }

  // 9a
  @Test
  void reasonsAreCopiedSoLaterChangesToTheOriginalListDoNotAffectTheDto() {
    List<String> original = new ArrayList<>(List.of("a"));
    TransferNotificationDto dto = notification(TransferStatusEnum.REJECTED, original);

    original.add("b");

    assertThat(dto.reasons()).containsExactly("a");
  }

  // 9b
  @Test
  void reasonsListReturnedByTheDtoIsImmutable() {
    TransferNotificationDto dto = notification(TransferStatusEnum.REJECTED, new ArrayList<>(List.of("a")));

    assertThatThrownBy(() -> dto.reasons().add("x")).isInstanceOf(UnsupportedOperationException.class);
  }

  // 10a-10d: empty strings
  @Test
  void emptyTransferIdIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus("", SOURCE_IBAN, DEST_IBAN, AMOUNT, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("transferId");
  }

  @Test
  void emptySourceIbanIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, "", DEST_IBAN, AMOUNT, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("sourceIban");
  }

  @Test
  void emptyDestIbanIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, SOURCE_IBAN, "", AMOUNT, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("destIban");
  }

  @Test
  void emptyCurrencyIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, "", TransferStatusEnum.COMPLETED))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("currency");
  }

  // 10e-10h: whitespace-only strings
  @Test
  void blankTransferIdIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus("   ", SOURCE_IBAN, DEST_IBAN, AMOUNT, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("transferId");
  }

  @Test
  void blankSourceIbanIsRefusedWithoutLeakingSensitiveData() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, "   ", DEST_IBAN, AMOUNT, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("sourceIban")
      .message()
      .doesNotContain(SOURCE_IBAN, DEST_IBAN, AMOUNT.toPlainString());
  }

  @Test
  void blankDestIbanIsRefusedWithoutLeakingSensitiveData() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, SOURCE_IBAN, "   ", AMOUNT, CURRENCY, TransferStatusEnum.COMPLETED))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("destIban")
      .message()
      .doesNotContain(SOURCE_IBAN, DEST_IBAN, AMOUNT.toPlainString());
  }

  @Test
  void blankCurrencyIsRefused() {
    assertThatThrownBy(() -> notificationWithStatus(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, AMOUNT, "   ", TransferStatusEnum.COMPLETED))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("currency");
  }

  // 11
  @Test
  void blankReasonAmongValidOnesIsRefused() {
    assertThatThrownBy(() -> notification(TransferStatusEnum.REJECTED, List.of(LOCKED, "  ")))
      .isInstanceOf(IllegalArgumentException.class);
  }

  // 12
  @Test
  void emptyReasonIsRefused() {
    assertThatThrownBy(() -> notification(TransferStatusEnum.REJECTED, List.of("")))
      .isInstanceOf(IllegalArgumentException.class);
  }

  // 13
  @Test
  void nullReasonInsideTheListIsRefused() {
    assertThatThrownBy(() -> notification(TransferStatusEnum.REJECTED, Arrays.asList("a", null)))
      .isInstanceOf(NullPointerException.class);
  }

  // 14
  @Test
  void currencyLengthAndAmountDecimalsAreNotCheckedByTheDto() {
    assertThatCode(() -> new TransferNotificationDto(
      TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, new BigDecimal("1.234"), "ABCD", TransferStatusEnum.COMPLETED, List.of()))
      .doesNotThrowAnyException();

    TransferNotificationDto dto = new TransferNotificationDto(
      TRANSFER_ID, SOURCE_IBAN, DEST_IBAN, new BigDecimal("1.234"), "ABCD", TransferStatusEnum.COMPLETED, List.of());
    assertThat(dto.amount()).isEqualByComparingTo("1.234");
    assertThat(dto.currency()).isEqualTo("ABCD");
  }

  @Test
  void unknownStatusIsRefused() {
    assertThatThrownBy(() -> notification(TransferStatusEnum.UNKNOWN, List.of()))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> notification(TransferStatusEnum.UNKNOWN, List.of(LOCKED)))
      .isInstanceOf(IllegalArgumentException.class);
  }
}
