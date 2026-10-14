package com.bank.notification.mongo.repository.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

class TransferNotificationDocumentTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";
  private static final long OCCURRED_AT_MILLIS = 1_791_883_800_000L;

  @Test
  void toStringContainsTransferIdAndStatusButNeitherIban() {
    TransferNotificationDocument document = document(List.of(), new Date(OCCURRED_AT_MILLIS));

    String text = document.toString();

    assertThat(text).contains(TRANSFER_ID).contains("COMPLETED");
    assertThat(text).doesNotContain(SOURCE).doesNotContain(DESTINATION);
    assertThat(text).doesNotContain("0542811101");
  }

  @Test
  void doesNotChangeWhenTheMutableReasonsListIsModifiedAfterConstruction() {
    List<String> reasons = new ArrayList<>(List.of("first", "second"));
    TransferNotificationDocument document = document(reasons, new Date(OCCURRED_AT_MILLIS));

    reasons.add("third");
    reasons.set(0, "changed");

    assertThat(document.getReasons()).containsExactly("first", "second");
  }

  @Test
  void returnsAnUnmodifiableReasonsList() {
    TransferNotificationDocument document =
        document(List.of("first"), new Date(OCCURRED_AT_MILLIS));

    List<String> reasons = document.getReasons();

    assertThatThrownBy(() -> reasons.add("second"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(document.getReasons()).containsExactly("first");
  }

  @Test
  void doesNotChangeWhenTheOriginalDateIsModifiedAfterConstruction() {
    Date original = new Date(OCCURRED_AT_MILLIS);
    TransferNotificationDocument document = document(List.of(), original);

    original.setTime(0L);

    assertThat(document.getOccurredAt()).isEqualTo(new Date(OCCURRED_AT_MILLIS));
  }

  @Test
  void doesNotChangeWhenTheReturnedDateIsModified() {
    TransferNotificationDocument document = document(List.of(), new Date(OCCURRED_AT_MILLIS));

    document.getOccurredAt().setTime(0L);

    assertThat(document.getOccurredAt()).isEqualTo(new Date(OCCURRED_AT_MILLIS));
  }

  @Test
  void exposesAllFieldsThroughGetters() {
    Date occurredAt = new Date(OCCURRED_AT_MILLIS);
    TransferNotificationDocument document = new TransferNotificationDocument(TRANSFER_ID, SOURCE,
        DESTINATION, new Decimal128(new BigDecimal("100.25")), "EUR", "REJECTED",
        List.of("a", "b"), occurredAt);

    assertThat(document.getTransferId()).isEqualTo(TRANSFER_ID);
    assertThat(document.getSourceIban()).isEqualTo(SOURCE);
    assertThat(document.getDestinationIban()).isEqualTo(DESTINATION);
    assertThat(document.getAmount().bigDecimalValue()).isEqualByComparingTo("100.25");
    assertThat(document.getCurrency()).isEqualTo("EUR");
    assertThat(document.getStatus()).isEqualTo("REJECTED");
    assertThat(document.getReasons()).containsExactly("a", "b");
    assertThat(document.getOccurredAt()).isEqualTo(occurredAt);
  }

  private static TransferNotificationDocument document(List<String> reasons, Date occurredAt) {
    return new TransferNotificationDocument(TRANSFER_ID, SOURCE, DESTINATION,
        new Decimal128(new BigDecimal("100.25")), "EUR", "COMPLETED", reasons, occurredAt);
  }
}
