package com.bank.transfer.application.service;

import static com.bank.transfer.application.service.RecordingMetricsOutPort.concluded;
import static com.bank.transfer.application.service.RecordingMetricsOutPort.reconciliation;
import static com.bank.transfer.application.service.RecordingMetricsOutPort.refunded;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;

import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import com.bank.transfer.application.dto.ReconciliationOutcomeEnum;
import com.bank.transfer.application.dto.RefundCauseEnum;
import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.ClearingHouseOutPort;
import com.bank.transfer.application.port.out.ExternalTransferRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.NotificationOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Metriche della riconciliazione (piano transfer-14, D8, D11): un esito di riconciliazione per ogni id esaminato,
 * la conclusione e il rimborso solo se l'esito e' stato applicato, tutto dopo il commit.
 * Stesso setup di ExternalTransferSagaReconciliationTest.
 */
@ExtendWith(MockitoExtension.class)
class ExternalTransferSagaReconciliationMetricsTest {

  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final Iban SOURCE = new Iban(SOURCE_IBAN);
  private static final Iban DEST = new Iban(DEST_IBAN);
  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String OTHER_ID = "7c1d2e3f-0a1b-4c2d-8e3f-9a0b1c2d3e4f";
  private static final String THIRD_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d";
  private static final Instant THRESHOLD = Instant.parse("2026-03-10T12:00:00Z");
  private static final int MAX = 50;

  @Mock
  private AccountRepositoryOutPort accountRepository;

  @Mock
  private ExternalTransferRepositoryOutPort externalRepository;

  @Mock
  private FraudCheckOutPort fraudCheck;

  @Mock
  private NotificationOutPort notification;

  @Mock
  private ClearingHouseOutPort clearingHouse;

  @Mock
  private PlatformTransactionManager transactionManager;

  private Map<String, Account> accounts;
  private Map<String, Transfer> stored;

  private RecordingMetricsOutPort metrics;
  private ExternalTransferSagaServiceImpl service;

  @BeforeEach
  void setUp() {
    accounts = new HashMap<>();
    stored = new HashMap<>();

    lenient().when(accountRepository.findByIbanForUpdate(any(Iban.class)))
      .thenAnswer(call -> Optional.ofNullable(accounts.get(call.<Iban>getArgument(0).value())));
    lenient().when(accountRepository.save(any(Account.class))).thenAnswer(call -> {
      Account account = call.getArgument(0);
      accounts.put(account.iban().value(), account);
      return account;
    });
    lenient().doAnswer(call -> {
      Transfer transfer = call.getArgument(0);
      stored.put(transfer.id(), transfer);
      return null;
    }).when(externalRepository).save(any(Transfer.class), any());
    lenient().when(externalRepository.findByIdForUpdate(anyString()))
      .thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));

    metrics = new RecordingMetricsOutPort();
    service = newService(metrics);
  }

  private ExternalTransferSagaServiceImpl newService(RecordingMetricsOutPort port) {
    return new ExternalTransferSagaServiceImpl(accountRepository, externalRepository, fraudCheck, notification,
      clearingHouse, new TransactionTemplate(transactionManager), port);
  }

  // ------------------------------------------------------------------ helpers

  private void givenAccount(String amount) {
    accounts.put(SOURCE_IBAN, new Account("acc-0001", SOURCE, new Money(new BigDecimal(amount), "EUR")));
  }

  private void givenStoredTransfer(String id, TransferStatusEnum status, String amount) {
    stored.put(id, new Transfer(id, SOURCE, DEST, new Money(new BigDecimal(amount), "EUR"), status));
  }

  private void givenStaleIds(String... ids) {
    lenient().when(externalRepository.findInProgressIdsCreatedBefore(any(Instant.class), anyInt()))
      .thenReturn(List.of(ids));
  }

  private void givenStatus(String id, ClearingOutcomeDto outcome) {
    lenient().when(clearingHouse.queryStatus(id)).thenReturn(Optional.of(outcome));
  }

  private void givenNotFound(String id) {
    lenient().when(clearingHouse.queryStatus(id)).thenReturn(Optional.empty());
  }

  private static ClearingOutcomeDto accepted(String id) {
    return new ClearingOutcomeDto(id, ClearingDecisionEnum.ACCEPTED, null);
  }

  private static ClearingOutcomeDto rejected(String id, String reason) {
    return new ClearingOutcomeDto(id, ClearingDecisionEnum.REJECTED, reason);
  }

  private long commitsSoFar() {
    return mockingDetails(transactionManager).getInvocations().stream()
      .filter(invocation -> invocation.getMethod().getName().equals("commit")).count();
  }

  // ------------------------------------------------------------------ le tre risposte della stanza

  @Test
  void staleSentWithAnAcceptedStatusCountsReconciliationAndConclusionAsCompleted() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, accepted(TRANSFER_ID));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      reconciliation(ReconciliationOutcomeEnum.COMPLETED),
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED));
  }

  @Test
  void staleUnknownWithAnAcceptedStatusCountsReconciliationAndConclusionAsCompleted() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.UNKNOWN, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, accepted(TRANSFER_ID));

    service.reconcileStale(THRESHOLD, MAX);

    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      reconciliation(ReconciliationOutcomeEnum.COMPLETED),
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED));
  }

  @Test
  void staleTransferWithARejectedStatusCountsReconciliationConclusionAndClearingRejectedRefund() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, rejected(TRANSFER_ID, "R"));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      reconciliation(ReconciliationOutcomeEnum.REJECTED),
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED),
      refunded(RefundCauseEnum.CLEARING_REJECTED));
  }

  @Test
  void staleTransferNotFoundAtTheClearingHouseCountsNotFoundConclusionAndNotFoundRefund() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenNotFound(TRANSFER_ID);

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      reconciliation(ReconciliationOutcomeEnum.NOT_FOUND),
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED),
      refunded(RefundCauseEnum.NOT_FOUND_AT_CLEARING));
  }

  // ------------------------------------------------------------------ SKIPPED

  @ParameterizedTest
  @EnumSource(value = TransferStatusEnum.class, names = {"COMPLETED", "REJECTED"})
  void idAlreadyConcludedCountsOnlySkipped(TransferStatusEnum alreadyConcluded) {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, alreadyConcluded, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, accepted(TRANSFER_ID));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isZero();
    assertThat(metrics.calls()).containsExactly(reconciliation(ReconciliationOutcomeEnum.SKIPPED));
  }

  @Test
  void notFoundAnswerOnAnAlreadyConcludedTransferCountsOnlySkippedAndNoRefund() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.COMPLETED, "30");
    givenStaleIds(TRANSFER_ID);
    givenNotFound(TRANSFER_ID);

    service.reconcileStale(THRESHOLD, MAX);

    assertThat(metrics.calls()).containsExactly(reconciliation(ReconciliationOutcomeEnum.SKIPPED));
  }

  @Test
  void idNoLongerInTheRepositoryCountsOnlySkipped() {
    givenAccount("70");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, accepted(TRANSFER_ID));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isZero();
    assertThat(metrics.calls()).containsExactly(reconciliation(ReconciliationOutcomeEnum.SKIPPED));
  }

  // ------------------------------------------------------------------ FAILED

  @Test
  void aFailingQueryCountsFailedAndTheOtherIdsGoOn() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStoredTransfer(OTHER_ID, TransferStatusEnum.SENT, "10");
    givenStaleIds(TRANSFER_ID, OTHER_ID);
    lenient().when(clearingHouse.queryStatus(TRANSFER_ID)).thenThrow(new IllegalStateException("boom"));
    givenStatus(OTHER_ID, accepted(OTHER_ID));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      reconciliation(ReconciliationOutcomeEnum.FAILED),
      reconciliation(ReconciliationOutcomeEnum.COMPLETED),
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED));
  }

  @Test
  void aFailingApplyCountsFailedAndNoConclusionNorRefund() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, rejected(TRANSFER_ID, "R"));
    lenient().doThrow(new IllegalStateException("outbox write failed"))
      .when(notification).publish(any(TransferNotificationDto.class));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isZero();
    assertThat(metrics.calls()).containsExactly(reconciliation(ReconciliationOutcomeEnum.FAILED));
  }

  @Test
  void aFailingSaveOfNotFoundRefundCountsFailedAndNoRefund() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenNotFound(TRANSFER_ID);
    lenient().doThrow(new IllegalStateException("database down"))
      .when(externalRepository).save(any(Transfer.class), any());

    service.reconcileStale(THRESHOLD, MAX);

    assertThat(metrics.calls()).containsExactly(reconciliation(ReconciliationOutcomeEnum.FAILED));
  }

  // ------------------------------------------------------------------ piu' id

  @Test
  void everyExaminedIdGivesExactlyOneReconciliationOutcome() {
    givenAccount("1000");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStoredTransfer(OTHER_ID, TransferStatusEnum.UNKNOWN, "10");
    givenStoredTransfer(THIRD_ID, TransferStatusEnum.COMPLETED, "20");
    givenStaleIds(TRANSFER_ID, OTHER_ID, THIRD_ID);
    givenStatus(TRANSFER_ID, accepted(TRANSFER_ID));
    givenNotFound(OTHER_ID);
    givenStatus(THIRD_ID, accepted(THIRD_ID));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(2);
    assertThat(metrics.calls().stream().filter(call -> call.startsWith("reconciliation("))).containsExactly(
      reconciliation(ReconciliationOutcomeEnum.COMPLETED),
      reconciliation(ReconciliationOutcomeEnum.NOT_FOUND),
      reconciliation(ReconciliationOutcomeEnum.SKIPPED));
    assertThat(metrics.calls().stream().filter(call -> call.startsWith("concluded("))).hasSize(2);
    assertThat(metrics.calls().stream().filter(call -> call.startsWith("refunded("))).containsExactly(
      refunded(RefundCauseEnum.NOT_FOUND_AT_CLEARING));
  }

  @Test
  void sameRejectedStatusInTwoRoundsCountsOneConclusionAndOneRefundAndASkippedTheSecondTime() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, rejected(TRANSFER_ID, "R"));

    service.reconcileStale(THRESHOLD, MAX);
    service.reconcileStale(THRESHOLD, MAX);

    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      reconciliation(ReconciliationOutcomeEnum.REJECTED),
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED),
      refunded(RefundCauseEnum.CLEARING_REJECTED),
      reconciliation(ReconciliationOutcomeEnum.SKIPPED));
  }

  // ------------------------------------------------------------------ dopo il commit (D11)

  @Test
  void everythingIsCountedAfterTheCommitOfTheOutcome() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenNotFound(TRANSFER_ID);
    List<Long> commitsAtCall = new ArrayList<>();
    RecordingMetricsOutPort probing = new RecordingMetricsOutPort() {
      @Override
      public void reconciliationExamined(ReconciliationOutcomeEnum outcome) {
        commitsAtCall.add(commitsSoFar());
        super.reconciliationExamined(outcome);
      }

      @Override
      public void transferConcluded(TransferTypeEnum type, TransferStatusEnum status) {
        commitsAtCall.add(commitsSoFar());
        super.transferConcluded(type, status);
      }

      @Override
      public void transferRefunded(RefundCauseEnum cause) {
        commitsAtCall.add(commitsSoFar());
        super.transferRefunded(cause);
      }
    };

    newService(probing).reconcileStale(THRESHOLD, MAX);

    assertThat(commitsAtCall).hasSize(3).containsOnly(1L);
  }

  // ------------------------------------------------------------------ niente da esaminare, argomenti non validi

  @Test
  void noStaleIdCountsNothing() {
    givenStaleIds();

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isZero();
    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void invalidArgumentsThrowAsTodayAndCountNothing() {
    assertThatThrownBy(() -> service.reconcileStale(null, MAX)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.reconcileStale(THRESHOLD, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.reconcileStale(THRESHOLD, -3)).isInstanceOf(IllegalArgumentException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void aFailingListOfStaleIdsIsPropagatedAndCountsNothing() {
    IllegalStateException failure = new IllegalStateException("query failed");
    lenient().when(externalRepository.findInProgressIdsCreatedBefore(any(Instant.class), anyInt()))
      .thenThrow(failure);

    assertThatThrownBy(() -> service.reconcileStale(THRESHOLD, MAX)).isSameAs(failure);

    assertThat(metrics.calls()).isEmpty();
  }
}
