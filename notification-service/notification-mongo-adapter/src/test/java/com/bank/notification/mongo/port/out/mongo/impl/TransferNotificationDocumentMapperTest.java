package com.bank.notification.mongo.port.out.mongo.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.bank.notification.domain.Iban;
import com.bank.notification.domain.Money;
import com.bank.notification.domain.TransferNotification;
import com.bank.notification.domain.TransferOutcome;
import com.bank.notification.mongo.repository.model.TransferNotificationDocument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

class TransferNotificationDocumentMapperTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";
  private static final String REJECTED_SOURCE = "IT60X0542811101000000000099";
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-13T09:30:00Z");

  private final TransferNotificationDocumentMapper mapper = new TransferNotificationDocumentMapper();

  @Test
  void mapsACompletedNotificationWithEmptyReasons() {
    TransferNotification notification = completed(new BigDecimal("100.25"), OCCURRED_AT);

    TransferNotificationDocument document = mapper.toDocument(notification);

    assertThat(document.getTransferId()).isEqualTo(TRANSFER_ID);
    assertThat(document.getSourceIban()).isEqualTo(SOURCE);
    assertThat(document.getDestinationIban()).isEqualTo(DESTINATION);
    assertThat(document.getCurrency()).isEqualTo("EUR");
    assertThat(document.getStatus()).isEqualTo("COMPLETED");
    assertThat(document.getReasons()).isEmpty();
    assertThat(document.getOccurredAt()).isEqualTo(Date.from(OCCURRED_AT));
  }

  @Test
  void mapsARejectedNotificationWithTheTwoReasonsInOrder() {
    TransferNotification notification = rejected(
        List.of("Amount must not exceed 10000 EUR", "Source IBAN is locked"));

    TransferNotificationDocument document = mapper.toDocument(notification);

    assertThat(document.getStatus()).isEqualTo("REJECTED");
    assertThat(document.getSourceIban()).isEqualTo(REJECTED_SOURCE);
    assertThat(document.getAmount().bigDecimalValue()).isEqualByComparingTo("20000.00");
    assertThat(document.getReasons())
        .containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
  }

  @Test
  void writesTheAmountAsDecimal128WithScaleTwo() {
    TransferNotificationDocument document =
        mapper.toDocument(completed(new BigDecimal("100.25"), OCCURRED_AT));

    BigDecimal value = document.getAmount().bigDecimalValue();

    assertThat(value).isEqualByComparingTo("100.25");
    assertThat(value.scale()).isEqualTo(2);
  }

  @Test
  void padsAnAmountWithOneDecimalToScaleTwo() {
    TransferNotificationDocument document =
        mapper.toDocument(completed(new BigDecimal("100.5"), OCCURRED_AT));

    BigDecimal value = document.getAmount().bigDecimalValue();

    assertThat(value).isEqualByComparingTo("100.50");
    assertThat(value.scale()).isEqualTo(2);
  }

  @Test
  void padsAnIntegerAmountToScaleTwo() {
    TransferNotificationDocument document =
        mapper.toDocument(completed(new BigDecimal("100"), OCCURRED_AT));

    BigDecimal value = document.getAmount().bigDecimalValue();

    assertThat(value).isEqualByComparingTo("100.00");
    assertThat(value.scale()).isEqualTo(2);
  }

  @Test
  void reducesAnAmountWithTrailingZerosToScaleTwoWithoutArithmeticException() {
    TransferNotificationDocument document =
        mapper.toDocument(completed(new BigDecimal("100.250"), OCCURRED_AT));

    BigDecimal value = document.getAmount().bigDecimalValue();

    assertThat(value).isEqualByComparingTo("100.25");
    assertThat(value.scale()).isEqualTo(2);
  }

  @Test
  void truncatesOccurredAtToMilliseconds() {
    Instant nanos = Instant.parse("2026-10-13T09:30:00.123456789Z");

    TransferNotificationDocument document =
        mapper.toDocument(completed(new BigDecimal("100.25"), nanos));

    assertThat(document.getOccurredAt())
        .isEqualTo(Date.from(Instant.parse("2026-10-13T09:30:00.123Z")));
  }

  // The domain Iban strips whitespace and upper-cases the value, so the document holds the
  // normalized, complete IBAN.
  @Test
  void writesTheNormalizedIbansWhenTheDomainReceivedSpacesAndLowerCase() {
    TransferNotification notification = new TransferNotification(TRANSFER_ID,
        new Iban("it60 x054 2811 1010 0000 0000 001"), new Iban(" it60x0542811101000000000002 "),
        new Money(new BigDecimal("100.25"), "EUR"), TransferOutcome.COMPLETED, List.of(),
        OCCURRED_AT);

    TransferNotificationDocument document = mapper.toDocument(notification);

    assertThat(document.getSourceIban()).isEqualTo(SOURCE);
    assertThat(document.getDestinationIban()).isEqualTo(DESTINATION);
  }

  @Test
  void rejectsANullNotification() {
    assertThatNullPointerException().isThrownBy(() -> mapper.toDocument(null));
  }

  private static TransferNotification completed(BigDecimal amount, Instant occurredAt) {
    return new TransferNotification(TRANSFER_ID, new Iban(SOURCE), new Iban(DESTINATION),
        new Money(amount, "EUR"), TransferOutcome.COMPLETED, List.of(), occurredAt);
  }

  private static TransferNotification rejected(List<String> reasons) {
    return new TransferNotification(TRANSFER_ID, new Iban(REJECTED_SOURCE), new Iban(DESTINATION),
        new Money(new BigDecimal("20000.00"), "EUR"), TransferOutcome.REJECTED, reasons,
        OCCURRED_AT);
  }
}
