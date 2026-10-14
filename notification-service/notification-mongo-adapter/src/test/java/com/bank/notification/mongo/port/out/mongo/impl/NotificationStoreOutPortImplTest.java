package com.bank.notification.mongo.port.out.mongo.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.bank.notification.domain.Iban;
import com.bank.notification.domain.Money;
import com.bank.notification.domain.TransferNotification;
import com.bank.notification.domain.TransferOutcome;
import com.bank.notification.mongo.repository.model.TransferNotificationDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.List;

class NotificationStoreOutPortImplTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";
  private static final String REJECTED_SOURCE = "IT60X0542811101000000000099";
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-13T09:30:00Z");

  private RecordingTransferNotificationRepository repository;
  private NotificationStoreOutPortImpl port;

  @BeforeEach
  void setUp() {
    repository = new RecordingTransferNotificationRepository();
    port = new NotificationStoreOutPortImpl(repository);
  }

  @Test
  void savesACompletedNotificationOnceWithTheTransferIdAndMappedFields() {
    port.save(completed());

    assertThat(repository.saved()).hasSize(1);
    TransferNotificationDocument document = repository.saved().get(0);
    assertThat(document.getTransferId()).isEqualTo(TRANSFER_ID);
    assertThat(document.getSourceIban()).isEqualTo(SOURCE);
    assertThat(document.getDestinationIban()).isEqualTo(DESTINATION);
    assertThat(document.getAmount().bigDecimalValue()).isEqualByComparingTo("100.25");
    assertThat(document.getCurrency()).isEqualTo("EUR");
    assertThat(document.getStatus()).isEqualTo("COMPLETED");
    assertThat(document.getReasons()).isEmpty();
    assertThat(document.getOccurredAt()).isEqualTo(Date.from(OCCURRED_AT));
  }

  @Test
  void savesARejectedNotificationWithBothReasons() {
    port.save(rejected());

    assertThat(repository.saved()).hasSize(1);
    TransferNotificationDocument document = repository.saved().get(0);
    assertThat(document.getStatus()).isEqualTo("REJECTED");
    assertThat(document.getReasons())
        .containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
  }

  @Test
  void callsSaveTwiceWithTheSameIdWhenTheSameNotificationArrivesTwice() {
    TransferNotification notification = completed();

    port.save(notification);
    port.save(notification);

    assertThat(repository.saved()).hasSize(2);
    assertThat(repository.saved().get(0).getTransferId()).isEqualTo(TRANSFER_ID);
    assertThat(repository.saved().get(1).getTransferId()).isEqualTo(TRANSFER_ID);
  }

  @Test
  void letsTheSameExceptionThrownByTheRepositoryRiseUnwrapped() {
    RuntimeException failure = new IllegalStateException("database down");
    repository.failWith(failure);

    Throwable thrown = catchThrowable(() -> port.save(completed()));

    assertThat(thrown).isSameAs(failure);
  }

  @Test
  void rejectsANullNotificationWithoutCallingTheRepository() {
    assertThatNullPointerException().isThrownBy(() -> port.save(null));

    assertThat(repository.saved()).isEmpty();
  }

  @Test
  void rejectsANullRepository() {
    assertThatNullPointerException().isThrownBy(() -> new NotificationStoreOutPortImpl(null));
  }

  private static TransferNotification completed() {
    return new TransferNotification(TRANSFER_ID, new Iban(SOURCE), new Iban(DESTINATION),
        new Money(new BigDecimal("100.25"), "EUR"), TransferOutcome.COMPLETED, List.of(),
        OCCURRED_AT);
  }

  private static TransferNotification rejected() {
    return new TransferNotification(TRANSFER_ID, new Iban(REJECTED_SOURCE), new Iban(DESTINATION),
        new Money(new BigDecimal("20000.00"), "EUR"), TransferOutcome.REJECTED,
        List.of("Amount must not exceed 10000 EUR", "Source IBAN is locked"), OCCURRED_AT);
  }
}
