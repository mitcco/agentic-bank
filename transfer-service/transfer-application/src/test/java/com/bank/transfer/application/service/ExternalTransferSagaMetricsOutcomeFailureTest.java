package com.bank.transfer.application.service;

import static com.bank.transfer.application.service.RecordingMetricsOutPort.concluded;
import static com.bank.transfer.application.service.RecordingMetricsOutPort.reconciliation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import com.bank.transfer.application.dto.ReconciliationOutcomeEnum;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Guasto della porta delle metriche DOPO un esito gia applicato (processOutcomes e reconcileStale): la metrica sta
 * fuori dal try di applyOutcome / queryStatus, quindi l'eccezione si propaga, lo stato concluso resta, non si registra
 * FAILED e non c'e il log di errore dell'esito. Stesso setup di ExternalTransferSagaReconciliationMetricsTest.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ExternalTransferSagaMetricsOutcomeFailureTest {

  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final Iban SOURCE = new Iban(SOURCE_IBAN);
  private static final Iban DEST = new Iban(DEST_IBAN);
  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
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

    // debited 30 from 100: the account holds 70
    accounts.put(SOURCE_IBAN, new Account("acc-0001", SOURCE, new Money(new BigDecimal("70"), "EUR")));
    stored.put(TRANSFER_ID, new Transfer(TRANSFER_ID, SOURCE, DEST, new Money(new BigDecimal("30"), "EUR"),
      TransferStatusEnum.SENT));
  }

  private ExternalTransferSagaServiceImpl newService(RecordingMetricsOutPort metrics) {
    return new ExternalTransferSagaServiceImpl(accountRepository, externalRepository, fraudCheck, notification,
      clearingHouse, new TransactionTemplate(transactionManager), metrics);
  }

  /** Records every call, then throws on concluded(EXTERNAL, status). */
  private static RecordingMetricsOutPort failingOnConcluded(TransferStatusEnum status, RuntimeException failure) {
    return new RecordingMetricsOutPort() {
      @Override
      public void transferConcluded(TransferTypeEnum type, TransferStatusEnum concludedStatus) {
        super.transferConcluded(type, concludedStatus);
        if (type == TransferTypeEnum.EXTERNAL && concludedStatus == status) {
          throw failure;
        }
      }
    };
  }

  /** Records every call, then throws on reconciliationExamined(outcome). */
  private static RecordingMetricsOutPort failingOnReconciliation(ReconciliationOutcomeEnum outcome,
    RuntimeException failure) {
    return new RecordingMetricsOutPort() {
      @Override
      public void reconciliationExamined(ReconciliationOutcomeEnum examined) {
        super.reconciliationExamined(examined);
        if (examined == outcome) {
          throw failure;
        }
      }
    };
  }

  private void givenPolled(ClearingDecisionEnum decision) {
    lenient().when(clearingHouse.pollOutcomes())
      .thenReturn(List.of(new ClearingOutcomeDto(TRANSFER_ID, decision,
        decision == ClearingDecisionEnum.REJECTED ? "R" : null)));
  }

  private void givenStale() {
    lenient().when(externalRepository.findInProgressIdsCreatedBefore(any(Instant.class), anyInt()))
      .thenReturn(List.of(TRANSFER_ID));
  }

  private long count(List<String> calls, String call) {
    return calls.stream().filter(call::equals).count();
  }

  private void assertNoFailureRecordedOrLogged(RecordingMetricsOutPort metrics, CapturedOutput output) {
    assertThat(metrics.calls()).doesNotContain(reconciliation(ReconciliationOutcomeEnum.FAILED));
    assertThat(output.getAll()).doesNotContain("Outcome failed").doesNotContain("Reconciliation failed");
  }

  // ------------------------------------------------------------------ processOutcomes

  @Test
  void processOutcomesAcceptedWithFailingConcludedMetricPropagatesAndTheTransferStaysCompleted(
    CapturedOutput output) {
    RuntimeException failure = new IllegalStateException("metrics registry down");
    RecordingMetricsOutPort metrics = failingOnConcluded(TransferStatusEnum.COMPLETED, failure);
    givenPolled(ClearingDecisionEnum.ACCEPTED);

    assertThatThrownBy(() -> newService(metrics).processOutcomes()).isSameAs(failure);

    assertThat(stored.get(TRANSFER_ID).status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(accounts.get(SOURCE_IBAN).money().amount()).isEqualByComparingTo("70");
    assertThat(count(metrics.calls(), concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED)))
      .isEqualTo(1);
    assertNoFailureRecordedOrLogged(metrics, output);
  }

  @Test
  void processOutcomesRejectedWithFailingConcludedMetricPropagatesAndTheTransferStaysRejectedRefundedOnce(
    CapturedOutput output) {
    RuntimeException failure = new IllegalStateException("metrics registry down");
    RecordingMetricsOutPort metrics = failingOnConcluded(TransferStatusEnum.REJECTED, failure);
    givenPolled(ClearingDecisionEnum.REJECTED);

    assertThatThrownBy(() -> newService(metrics).processOutcomes()).isSameAs(failure);

    assertThat(stored.get(TRANSFER_ID).status()).isEqualTo(TransferStatusEnum.REJECTED);
    // refunded once (70 + 30), not again
    assertThat(accounts.get(SOURCE_IBAN).money().amount()).isEqualByComparingTo("100");
    assertThat(count(metrics.calls(), concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED)))
      .isEqualTo(1);
    assertNoFailureRecordedOrLogged(metrics, output);
  }

  // ------------------------------------------------------------------ reconcileStale

  @Test
  void reconcileStaleAcceptedWithFailingReconciliationMetricPropagatesAndTheTransferStaysCompleted(
    CapturedOutput output) {
    RuntimeException failure = new IllegalStateException("metrics registry down");
    RecordingMetricsOutPort metrics = failingOnReconciliation(ReconciliationOutcomeEnum.COMPLETED, failure);
    givenStale();
    lenient().when(clearingHouse.queryStatus(TRANSFER_ID))
      .thenReturn(Optional.of(new ClearingOutcomeDto(TRANSFER_ID, ClearingDecisionEnum.ACCEPTED, null)));

    assertThatThrownBy(() -> newService(metrics).reconcileStale(THRESHOLD, MAX)).isSameAs(failure);

    assertThat(stored.get(TRANSFER_ID).status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(accounts.get(SOURCE_IBAN).money().amount()).isEqualByComparingTo("70");
    assertThat(count(metrics.calls(), concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED)))
      .isEqualTo(1);
    assertThat(count(metrics.calls(), reconciliation(ReconciliationOutcomeEnum.COMPLETED))).isEqualTo(1);
    assertNoFailureRecordedOrLogged(metrics, output);
  }

  @Test
  void reconcileStaleNotFoundWithFailingReconciliationMetricPropagatesAndTheTransferStaysRejected(
    CapturedOutput output) {
    RuntimeException failure = new IllegalStateException("metrics registry down");
    RecordingMetricsOutPort metrics = failingOnReconciliation(ReconciliationOutcomeEnum.NOT_FOUND, failure);
    givenStale();
    lenient().when(clearingHouse.queryStatus(TRANSFER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> newService(metrics).reconcileStale(THRESHOLD, MAX)).isSameAs(failure);

    assertThat(stored.get(TRANSFER_ID).status()).isEqualTo(TransferStatusEnum.REJECTED);
    // refunded once (70 + 30), not again
    assertThat(accounts.get(SOURCE_IBAN).money().amount()).isEqualByComparingTo("100");
    assertThat(count(metrics.calls(), concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED)))
      .isEqualTo(1);
    assertThat(count(metrics.calls(), reconciliation(ReconciliationOutcomeEnum.NOT_FOUND))).isEqualTo(1);
    assertNoFailureRecordedOrLogged(metrics, output);
  }
}
