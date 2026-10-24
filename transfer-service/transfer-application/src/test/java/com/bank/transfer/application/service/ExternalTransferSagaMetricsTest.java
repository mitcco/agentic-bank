package com.bank.transfer.application.service;

import static com.bank.transfer.application.service.RecordingMetricsOutPort.concluded;
import static com.bank.transfer.application.service.RecordingMetricsOutPort.refunded;
import static com.bank.transfer.application.service.RecordingMetricsOutPort.requested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.RefundCauseEnum;
import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.ClearingHouseTimeoutException;
import com.bank.transfer.application.exception.ClearingHouseUnavailableException;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.ClearingHouseOutPort;
import com.bank.transfer.application.port.out.ExternalTransferRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.NotificationOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import com.bank.transfer.domain.exception.DifferentCurrencyException;
import com.bank.transfer.domain.exception.InsufficientFundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
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
 * Metriche della Saga dei bonifici esterni (piano transfer-14, D1, D7, D11): requests e concluded si contano dopo il
 * commit, i rimborsi solo dove c'e' il riaccredito, un'eccezione con rollback non lascia nessun conteggio.
 * Repository con stato (risposte su mappe) come in ExternalTransferSagaServiceImplTest.
 */
@ExtendWith(MockitoExtension.class)
class ExternalTransferSagaMetricsTest {

  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final Iban SOURCE = new Iban(SOURCE_IBAN);
  private static final Iban DEST = new Iban(DEST_IBAN);
  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String OTHER_ID = "7c1d2e3f-0a1b-4c2d-8e3f-9a0b1c2d3e4f";

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

    lenient().when(accountRepository.findByIban(any(Iban.class)))
      .thenAnswer(call -> Optional.ofNullable(accounts.get(call.<Iban>getArgument(0).value())));
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

  private void givenAccount(String iban, String amount) {
    accounts.put(iban, new Account("acc-" + iban.substring(iban.length() - 4), new Iban(iban),
      new Money(new BigDecimal(amount), "EUR")));
  }

  private void givenFraudDecision(FraudStatusEnum decision, String... reasons) {
    lenient().when(fraudCheck.checkFraud(anyString(), anyString(), anyString(), any(BigDecimal.class), anyString()))
      .thenReturn(new FraudCheckResultDto(decision, List.of(reasons)));
  }

  private void givenSubmitThrows(Function<String, RuntimeException> exceptionForTransfer) {
    lenient().doAnswer(call -> {
      throw exceptionForTransfer.apply(call.<ClearingInstructionDto>getArgument(0).transferId());
    }).when(clearingHouse).submit(any(ClearingInstructionDto.class));
  }

  private void givenStoredTransfer(String id, TransferStatusEnum status, String amount) {
    stored.put(id, new Transfer(id, SOURCE, DEST, new Money(new BigDecimal(amount), "EUR"), status));
  }

  private void givenOutcomes(ClearingOutcomeDto... outcomes) {
    lenient().when(clearingHouse.pollOutcomes()).thenReturn(List.of(outcomes));
  }

  private static ClearingOutcomeDto accepted(String id) {
    return new ClearingOutcomeDto(id, ClearingDecisionEnum.ACCEPTED, null);
  }

  private static ClearingOutcomeDto rejected(String id, String reason) {
    return new ClearingOutcomeDto(id, ClearingDecisionEnum.REJECTED, reason);
  }

  private static CreateTransferCommand command(String amount) {
    return new CreateTransferCommand(SOURCE_IBAN, DEST_IBAN, new BigDecimal(amount), "EUR");
  }

  private long commitsSoFar() {
    return mockingDetails(transactionManager).getInvocations().stream()
      .filter(invocation -> invocation.getMethod().getName().equals("commit")).count();
  }

  private boolean submittedSoFar() {
    return mockingDetails(clearingHouse).getInvocations().stream()
      .anyMatch(invocation -> invocation.getMethod().getName().equals("submit"));
  }

  /** A metrics port that notes how many commits had happened (and whether submit was called) at each call. */
  private static final class Timeline {
    final List<Long> commitsAtCall = new ArrayList<>();
    final List<Boolean> submittedAtCall = new ArrayList<>();
  }

  private ExternalTransferSagaServiceImpl serviceWithTimeline(Timeline timeline) {
    RecordingMetricsOutPort probing = new RecordingMetricsOutPort() {
      private void note() {
        timeline.commitsAtCall.add(commitsSoFar());
        timeline.submittedAtCall.add(submittedSoFar());
      }

      @Override
      public void transferRequested(TransferTypeEnum type, TransferStatusEnum status) {
        note();
        super.transferRequested(type, status);
      }

      @Override
      public void transferConcluded(TransferTypeEnum type, TransferStatusEnum status) {
        note();
        super.transferConcluded(type, status);
      }

      @Override
      public void transferRefunded(RefundCauseEnum cause) {
        note();
        super.transferRefunded(cause);
      }
    };
    return newService(probing);
  }

  // ------------------------------------------------------------------ executeExternalTransfer

  @Test
  void fraudRejectionCountsRequestAndConclusionAsRejectedAndNoRefund() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.REJECTED, "A");

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED),
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED));
  }

  @Test
  void successfulSubmitCountsOnlyTheRequestAsSent() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("SENT");
    assertThat(metrics.calls()).containsExactly(requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.SENT));
  }

  @Test
  void unavailableClearingCountsConclusionRefundAndRequestAsRejected() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(ClearingHouseUnavailableException::new);

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED),
      refunded(RefundCauseEnum.CLEARING_UNAVAILABLE),
      requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED));
  }

  @Test
  void timeoutCountsOnlyTheRequestAsUnknown() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(ClearingHouseTimeoutException::new);

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("UNKNOWN");
    assertThat(metrics.calls()).containsExactly(requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.UNKNOWN));
  }

  @Test
  void unexpectedSubmitFailureCountsOnlyTheRequestAsUnknown() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(id -> new IllegalStateException("something went wrong"));

    service.executeExternalTransfer(command("30"));

    assertThat(metrics.calls()).containsExactly(requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.UNKNOWN));
  }

  @Test
  void timeoutWithTheOutcomeAlreadyAcceptedCountsTheReturnedStateAndNoExtraConclusion() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    lenient().doAnswer(call -> {
      String id = call.<ClearingInstructionDto>getArgument(0).transferId();
      stored.put(id, stored.get(id).settle());
      throw new ClearingHouseTimeoutException(id);
    }).when(clearingHouse).submit(any(ClearingInstructionDto.class));

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("COMPLETED");
    assertThat(metrics.calls()).containsExactly(requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED));
  }

  @Test
  void timeoutWithTheOutcomeAlreadyRejectedCountsTheReturnedStateAndNoExtraConclusionNorRefund() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    lenient().doAnswer(call -> {
      String id = call.<ClearingInstructionDto>getArgument(0).transferId();
      stored.put(id, stored.get(id).rejectAfterSent());
      throw new ClearingHouseTimeoutException(id);
    }).when(clearingHouse).submit(any(ClearingInstructionDto.class));

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(metrics.calls()).containsExactly(requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED));
  }

  @Test
  void missingSourceAccountThrowsAndNothingIsCounted() {
    givenFraudDecision(FraudStatusEnum.ALLOWED);

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30")))
      .isInstanceOf(AccountNotFoundException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void unavailableFraudCheckThrowsAndNothingIsCounted() {
    givenAccount(SOURCE_IBAN, "100");
    lenient().when(fraudCheck.checkFraud(anyString(), anyString(), anyString(), any(BigDecimal.class), anyString()))
      .thenThrow(new FraudCheckUnavailableException(TRANSFER_ID));

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30")))
      .isInstanceOf(FraudCheckUnavailableException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void insufficientFundsThrowsAndNothingIsCounted() {
    givenAccount(SOURCE_IBAN, "10");
    givenFraudDecision(FraudStatusEnum.ALLOWED);

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30")))
      .isInstanceOf(InsufficientFundException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void differentCurrencyThrowsAndNothingIsCounted() {
    accounts.put(SOURCE_IBAN, new Account("acc-0001", SOURCE, new Money(new BigDecimal("100"), "USD")));
    givenFraudDecision(FraudStatusEnum.ALLOWED);

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30")))
      .isInstanceOf(DifferentCurrencyException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void failingSaveOfTheSentTransferRollsBackAndNothingIsCounted() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    IllegalStateException failure = new IllegalStateException("database down");
    lenient().doThrow(failure).when(externalRepository).save(any(Transfer.class), any());

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30"))).isSameAs(failure);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void failingSaveOfAFraudRejectionRollsBackAndNothingIsCounted() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.REJECTED, "A");
    IllegalStateException failure = new IllegalStateException("database down");
    lenient().doThrow(failure).when(externalRepository).save(any(Transfer.class), any());

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30"))).isSameAs(failure);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void failingOutboxWriteOfAFraudRejectionRollsBackAndNothingIsCounted() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.REJECTED, "A");
    IllegalStateException failure = new IllegalStateException("outbox write failed");
    lenient().doThrow(failure).when(notification).publish(any(TransferNotificationDto.class));

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30"))).isSameAs(failure);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void failingImmediateCompensationRollsBackAndNothingIsCounted() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(ClearingHouseUnavailableException::new);
    IllegalStateException failure = new IllegalStateException("outbox write failed");
    lenient().doThrow(failure).when(notification).publish(any(TransferNotificationDto.class));

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30"))).isSameAs(failure);

    assertThat(metrics.calls()).isEmpty();
  }

  // ------------------------------------------------------------------ dopo il commit (D11)

  @Test
  void fraudRejectionIsCountedAfterItsCommit() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.REJECTED, "A");
    Timeline timeline = new Timeline();

    serviceWithTimeline(timeline).executeExternalTransfer(command("30"));

    assertThat(timeline.commitsAtCall).hasSize(2).containsOnly(1L);
  }

  @Test
  void sentRequestIsCountedAfterTheCommitOfSentAndAfterTheSubmit() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    Timeline timeline = new Timeline();

    serviceWithTimeline(timeline).executeExternalTransfer(command("30"));

    assertThat(timeline.commitsAtCall).containsExactly(1L);
    assertThat(timeline.submittedAtCall).containsExactly(true);
  }

  @Test
  void compensationIsCountedAfterTheCommitOfTheRefund() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(ClearingHouseUnavailableException::new);
    Timeline timeline = new Timeline();

    serviceWithTimeline(timeline).executeExternalTransfer(command("30"));

    // the commit of SENT and the commit of the refund have both happened
    assertThat(timeline.commitsAtCall).hasSize(3).containsOnly(2L);
  }

  @Test
  void unknownRequestIsCountedAfterTheCommitOfUnknown() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(ClearingHouseTimeoutException::new);
    Timeline timeline = new Timeline();

    serviceWithTimeline(timeline).executeExternalTransfer(command("30"));

    assertThat(timeline.commitsAtCall).containsExactly(2L);
  }

  // ------------------------------------------------------------------ processOutcomes

  @Test
  void acceptedOutcomeOnSentCountsOnlyTheConclusionAsCompleted() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenOutcomes(accepted(TRANSFER_ID));

    int applied = service.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(metrics.calls()).containsExactly(concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED));
  }

  @Test
  void rejectedOutcomeOnUnknownCountsConclusionAndRefundForTheClearingRejection() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.UNKNOWN, "30");
    givenOutcomes(rejected(TRANSFER_ID, "R"));

    int applied = service.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED),
      refunded(RefundCauseEnum.CLEARING_REJECTED));
  }

  @Test
  void rejectedOutcomeOnSentCountsConclusionAndRefundForTheClearingRejection() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenOutcomes(rejected(TRANSFER_ID, "R"));

    service.processOutcomes();

    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED),
      refunded(RefundCauseEnum.CLEARING_REJECTED));
  }

  @Test
  void acceptedOutcomeOnUnknownCountsTheConclusionAsCompleted() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.UNKNOWN, "30");
    givenOutcomes(accepted(TRANSFER_ID));

    service.processOutcomes();

    assertThat(metrics.calls()).containsExactly(concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED));
  }

  @ParameterizedTest
  @EnumSource(value = TransferStatusEnum.class, names = {"COMPLETED", "REJECTED"})
  void duplicateOutcomeOnAnAlreadyConcludedTransferCountsNothing(TransferStatusEnum alreadyConcluded) {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, alreadyConcluded, "30");
    givenOutcomes(accepted(TRANSFER_ID), rejected(TRANSFER_ID, "R"));

    int applied = service.processOutcomes();

    assertThat(applied).isZero();
    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void outcomeForAnUnknownIdCountsNothing() {
    givenAccount(SOURCE_IBAN, "70");
    givenOutcomes(accepted(OTHER_ID), rejected(OTHER_ID, "R"));

    int applied = service.processOutcomes();

    assertThat(applied).isZero();
    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void sameRejectedOutcomeTwiceCountsOneConclusionAndOneRefund() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    ClearingOutcomeDto rejection = rejected(TRANSFER_ID, "R");
    lenient().when(clearingHouse.pollOutcomes()).thenReturn(List.of(rejection, rejection));

    int applied = service.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED),
      refunded(RefundCauseEnum.CLEARING_REJECTED));
  }

  @Test
  void failingSaveInsideTheOutcomeCountsNothingAndTheReturnedValueIsUnchanged() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenOutcomes(accepted(TRANSFER_ID));
    lenient().doThrow(new IllegalStateException("database down"))
      .when(externalRepository).save(any(Transfer.class), any());

    int applied = service.processOutcomes();

    assertThat(applied).isZero();
    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void failingOutboxWriteInsideTheOutcomeCountsNothingForThatOutcomeButTheNextOneIsCounted() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStoredTransfer(OTHER_ID, TransferStatusEnum.SENT, "10");
    givenOutcomes(rejected(TRANSFER_ID, "R"), accepted(OTHER_ID));
    lenient().doAnswer(call -> {
      if (call.<TransferNotificationDto>getArgument(0).transferId().equals(TRANSFER_ID)) {
        throw new IllegalStateException("outbox write failed");
      }
      return null;
    }).when(notification).publish(any(TransferNotificationDto.class));

    int applied = service.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(metrics.calls()).containsExactly(concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED));
  }

  @Test
  void vanishedSourceAccountOnARejectedOutcomeCountsNothing() {
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenOutcomes(rejected(TRANSFER_ID, "R"));

    int applied = service.processOutcomes();

    assertThat(applied).isZero();
    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void noOutcomesCountNothing() {
    givenOutcomes();

    service.processOutcomes();

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void outcomesAreCountedAfterTheirCommit() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenOutcomes(rejected(TRANSFER_ID, "R"));
    Timeline timeline = new Timeline();

    serviceWithTimeline(timeline).processOutcomes();

    assertThat(timeline.commitsAtCall).hasSize(2).containsOnly(1L);
  }
}
