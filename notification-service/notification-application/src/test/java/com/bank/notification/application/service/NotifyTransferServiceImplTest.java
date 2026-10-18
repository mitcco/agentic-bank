package com.bank.notification.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.notification.application.command.NotifyTransferCommand;
import com.bank.notification.domain.TransferNotification;
import com.bank.notification.domain.TransferOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class NotifyTransferServiceImplTest {

  private static final String IBAN_1 = "IT60X0542811101000000000001";
  private static final String IBAN_2 = "IT60X0542811101000000000002";
  private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final Instant OCCURRED_AT = Instant.parse("2026-10-13T09:30:00Z");
  private static final String REASON_1 = "Amount must not exceed 10000 EUR";
  private static final String REASON_2 = "Source IBAN is locked";

  private final List<String> registry = new ArrayList<>();
  private final FakeNotificationStoreOutPort store = new FakeNotificationStoreOutPort(registry);
  private final FakeNotificationLogOutPort log = new FakeNotificationLogOutPort(registry);
  private final NotifyTransferServiceImpl service = new NotifyTransferServiceImpl(store, log);

  private static NotifyTransferCommand completed() {
    return new NotifyTransferCommand(
        ID, IBAN_1, IBAN_2, new BigDecimal("100.25"), "EUR", "COMPLETED", null, OCCURRED_AT);
  }

  private static NotifyTransferCommand rejected() {
    return new NotifyTransferCommand(
        ID, IBAN_1, IBAN_2, new BigDecimal("100.25"), "EUR", "REJECTED",
        List.of(REASON_1, REASON_2), OCCURRED_AT);
  }

  // Scenario 1
  @Test
  void validCompletedCommand_savesThenLogsTheSameNotification() {
    service.notifyTransfer(completed());

    assertThat(registry).containsExactly("save", "log");
    assertThat(store.saved()).hasSize(1);
    assertThat(log.logged()).hasSize(1);
    assertThat(store.saved().get(0)).isSameAs(log.logged().get(0));
    TransferNotification notification = store.saved().get(0);
    assertThat(notification.transferId()).isEqualTo(ID);
    assertThat(notification.sourceIban().value()).isEqualTo(IBAN_1);
    assertThat(notification.destinationIban().value()).isEqualTo(IBAN_2);
    assertThat(notification.amount().amount()).isEqualByComparingTo("100.25");
    assertThat(notification.amount().currency()).isEqualTo("EUR");
    assertThat(notification.outcome()).isEqualTo(TransferOutcome.COMPLETED);
    assertThat(notification.occurredAt()).isEqualTo(OCCURRED_AT);
    assertThat(notification.reasons()).isEmpty();
  }

  // Scenario 2
  @Test
  void rejectedCommandWithTwoReasons_savesReasonsInOrderThenLogs() {
    service.notifyTransfer(rejected());

    assertThat(registry).containsExactly("save", "log");
    TransferNotification notification = store.saved().get(0);
    assertThat(notification.outcome()).isEqualTo(TransferOutcome.REJECTED);
    assertThat(notification.reasons()).containsExactly(REASON_1, REASON_2);
  }

  // Scenario 9, application part
  @Test
  void storeFailure_propagatesSameExceptionAndLogIsNeverCalled() {
    RuntimeException failure = new RuntimeException("store down");
    store.throwing(failure);

    assertThatThrownBy(() -> service.notifyTransfer(completed())).isSameAs(failure);
    assertThat(registry).containsExactly("save");
    assertThat(log.logged()).isEmpty();
  }

  @Test
  void logFailure_propagatesSameExceptionAfterTheNotificationWasSaved() {
    RuntimeException failure = new RuntimeException("log down");
    log.throwing(failure);

    assertThatThrownBy(() -> service.notifyTransfer(completed())).isSameAs(failure);
    assertThat(store.saved()).hasSize(1);
    assertThat(registry).containsExactly("save", "log");
  }

  // Scenario 3, application part
  @Test
  void sameCommandSentTwice_savesAndLogsTwiceWithTheSameTransferId() {
    NotifyTransferCommand command = completed();

    service.notifyTransfer(command);
    service.notifyTransfer(command);

    assertThat(registry).containsExactly("save", "log", "save", "log");
    assertThat(store.saved()).hasSize(2);
    assertThat(store.saved().get(0).transferId()).isEqualTo(ID);
    assertThat(store.saved().get(1).transferId()).isEqualTo(ID);
  }

  @Test
  void nullStore_throwsNullPointerException() {
    assertThatThrownBy(() -> new NotifyTransferServiceImpl(null, log))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullLog_throwsNullPointerException() {
    assertThatThrownBy(() -> new NotifyTransferServiceImpl(store, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullCommand_throwsNullPointerExceptionAndCallsNoPort() {
    assertThatThrownBy(() -> service.notifyTransfer(null))
        .isInstanceOf(NullPointerException.class);
    assertThat(registry).isEmpty();
  }
}
