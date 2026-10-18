package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.application.port.in.ReconcileExternalTransfersInPort;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reconciliation of the stale external transfers: ask the clearing house (outside of any transaction), apply
 * the answer with the rules of the outcomes, refund only when the clearing house confirms it never got the
 * instruction. Same setup as ExternalTransferSagaServiceImplTest: stateful repositories over maps.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ExternalTransferSagaReconciliationTest {

  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final Iban SOURCE = new Iban(SOURCE_IBAN);
  private static final Iban DEST = new Iban(DEST_IBAN);
  private static final String NOT_FOUND_REASON = "Instruction not found at the clearing house";
  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String OTHER_ID = "7c1d2e3f-0a1b-4c2d-8e3f-9a0b1c2d3e4f";
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
  private Map<String, String> storedReasons;

  private ExternalTransferSagaServiceImpl service;

  @BeforeEach
  void setUp() {
    accounts = new HashMap<>();
    stored = new HashMap<>();
    storedReasons = new HashMap<>();

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
      storedReasons.put(transfer.id(), call.getArgument(1));
      return null;
    }).when(externalRepository).save(any(Transfer.class), any());
    lenient().when(externalRepository.findByIdForUpdate(anyString()))
      .thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));

    service = new ExternalTransferSagaServiceImpl(accountRepository, externalRepository, fraudCheck, notification,
      clearingHouse, new TransactionTemplate(transactionManager));
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

  private static Transfer savedAs(TransferStatusEnum status) {
    return argThat(transfer -> transfer != null && transfer.status() == status);
  }

  private TransferNotificationDto publishedOnce() {
    ArgumentCaptor<TransferNotificationDto> published = ArgumentCaptor.forClass(TransferNotificationDto.class);
    verify(notification, times(1)).publish(published.capture());
    return published.getValue();
  }

  private void verifyNoAccountTouched() {
    verify(accountRepository, never()).findByIbanForUpdate(any(Iban.class));
    verify(accountRepository, never()).save(any(Account.class));
  }

  private BigDecimal balance() {
    return accounts.get(SOURCE_IBAN).money().amount();
  }

  // ------------------------------------------------------------------ shape

  @Test
  void theSagaAlsoImplementsTheReconcileInPort() {
    assertThat(ReconcileExternalTransfersInPort.class.isAssignableFrom(ExternalTransferSagaServiceImpl.class))
      .isTrue();
  }

  // ------------------------------------------------------------------ the three answers of the clearing house

  @Test
  void staleSentWithAnAcceptedStatusIsCompleted() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, accepted(TRANSFER_ID));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    verify(externalRepository).save(savedAs(TransferStatusEnum.COMPLETED), isNull());
    verify(externalRepository, times(1)).save(any(Transfer.class), any());
    TransferNotificationDto published = publishedOnce();
    assertThat(published.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(published.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(published.reasons()).isEmpty();
    verifyNoAccountTouched();
    assertThat(balance()).isEqualByComparingTo("70");
    assertThat(stored.get(TRANSFER_ID).status()).isEqualTo(TransferStatusEnum.COMPLETED);
  }

  @Test
  void staleUnknownWithAnAcceptedStatusIsCompleted() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.UNKNOWN, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, accepted(TRANSFER_ID));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    verify(externalRepository).save(savedAs(TransferStatusEnum.COMPLETED), isNull());
    assertThat(publishedOnce().status()).isEqualTo(TransferStatusEnum.COMPLETED);
    verifyNoAccountTouched();
    assertThat(balance()).isEqualByComparingTo("70");
  }

  @Test
  void staleUnknownWithARejectedStatusIsRefunded() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.UNKNOWN, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, rejected(TRANSFER_ID, "R"));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    InOrder order = inOrder(externalRepository, accountRepository, notification);
    order.verify(externalRepository).findByIdForUpdate(TRANSFER_ID);
    order.verify(accountRepository).findByIbanForUpdate(SOURCE);
    order.verify(accountRepository).save(any(Account.class));
    order.verify(externalRepository).save(savedAs(TransferStatusEnum.REJECTED), eq("R"));
    order.verify(notification).publish(any(TransferNotificationDto.class));
    assertThat(balance()).isEqualByComparingTo("100");
    TransferNotificationDto published = publishedOnce();
    assertThat(published.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(published.reasons()).containsExactly("R");
    assertThat(storedReasons.get(TRANSFER_ID)).isEqualTo("R");
  }

  @Test
  void instructionNotFoundIsRefundedWithTheReason() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenNotFound(TRANSFER_ID);

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    InOrder order = inOrder(externalRepository, accountRepository, notification);
    order.verify(externalRepository).findByIdForUpdate(TRANSFER_ID);
    order.verify(accountRepository).findByIbanForUpdate(SOURCE);
    order.verify(accountRepository).save(any(Account.class));
    order.verify(externalRepository).save(savedAs(TransferStatusEnum.REJECTED), eq(NOT_FOUND_REASON));
    order.verify(notification).publish(any(TransferNotificationDto.class));
    assertThat(balance()).isEqualByComparingTo("100");
    TransferNotificationDto published = publishedOnce();
    assertThat(published.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(published.reasons()).containsExactly(NOT_FOUND_REASON);
  }

  // ------------------------------------------------------------------ idempotency

  @Test
  void transferConcludedMeanwhileIsIgnored() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.REJECTED, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, accepted(TRANSFER_ID));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isZero();
    verify(externalRepository, never()).save(any(Transfer.class), any());
    verifyNoInteractions(notification);
    verifyNoAccountTouched();
    assertThat(stored.get(TRANSFER_ID).status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(balance()).isEqualByComparingTo("70");
  }

  @Test
  void notFoundAnswerOnATransferConcludedMeanwhileDoesNotRefund() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.COMPLETED, "30");
    givenStaleIds(TRANSFER_ID);
    givenNotFound(TRANSFER_ID);

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isZero();
    verifyNoAccountTouched();
    verify(externalRepository, never()).save(any(Transfer.class), any());
    verifyNoInteractions(notification);
    assertThat(balance()).isEqualByComparingTo("70");
  }

  @Test
  void sameRejectedStatusInTwoRoundsRefundsOnce() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, rejected(TRANSFER_ID, "R"));

    int firstRound = service.reconcileStale(THRESHOLD, MAX);
    int secondRound = service.reconcileStale(THRESHOLD, MAX);

    assertThat(firstRound).isEqualTo(1);
    assertThat(secondRound).isZero();
    assertThat(balance()).isEqualByComparingTo("100");
    verify(accountRepository, times(1)).save(any(Account.class));
    verify(externalRepository, times(1)).save(savedAs(TransferStatusEnum.REJECTED), eq("R"));
    publishedOnce();
  }

  // ------------------------------------------------------------------ transactions

  @Test
  void queryStatusIsCalledOutsideTheTransaction() {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStaleIds(TRANSFER_ID);
    givenStatus(TRANSFER_ID, accepted(TRANSFER_ID));

    service.reconcileStale(THRESHOLD, MAX);

    InOrder order = inOrder(externalRepository, clearingHouse, transactionManager);
    order.verify(externalRepository).findInProgressIdsCreatedBefore(THRESHOLD, MAX);
    order.verify(clearingHouse).queryStatus(TRANSFER_ID);
    order.verify(transactionManager).getTransaction(any());
    order.verify(externalRepository).findByIdForUpdate(TRANSFER_ID);
    order.verify(transactionManager).commit(any());
    verify(transactionManager, times(1)).commit(any());
  }

  @Test
  void theListOfStaleIdsIsReadOutsideTheTransaction() {
    givenStaleIds(TRANSFER_ID);
    givenNotFound(TRANSFER_ID);
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");

    service.reconcileStale(THRESHOLD, MAX);

    InOrder order = inOrder(externalRepository, transactionManager);
    order.verify(externalRepository).findInProgressIdsCreatedBefore(THRESHOLD, MAX);
    order.verify(transactionManager).getTransaction(any());
  }

  // ------------------------------------------------------------------ failures

  @Test
  void aFailingQuerySkipsOnlyThatTransfer(CapturedOutput output) {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStoredTransfer(OTHER_ID, TransferStatusEnum.SENT, "10");
    givenStaleIds(TRANSFER_ID, OTHER_ID);
    lenient().when(clearingHouse.queryStatus(TRANSFER_ID)).thenThrow(new IllegalStateException("SENSITIVE-QUERY"));
    givenStatus(OTHER_ID, accepted(OTHER_ID));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    verify(externalRepository, never()).findByIdForUpdate(TRANSFER_ID);
    verify(externalRepository).save(savedAs(TransferStatusEnum.COMPLETED), isNull());
    assertThat(stored.get(TRANSFER_ID).status()).isEqualTo(TransferStatusEnum.SENT);
    assertThat(stored.get(OTHER_ID).status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(output.getAll()).contains(TRANSFER_ID, "IllegalStateException").doesNotContain("SENSITIVE");
  }

  @Test
  void aFailingApplyIsRolledBackAndTheNextTransferIsStillReconciled(CapturedOutput output) {
    givenAccount("70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenStoredTransfer(OTHER_ID, TransferStatusEnum.SENT, "10");
    givenStaleIds(TRANSFER_ID, OTHER_ID);
    givenStatus(TRANSFER_ID, rejected(TRANSFER_ID, "R"));
    givenStatus(OTHER_ID, accepted(OTHER_ID));
    lenient().doAnswer(call -> {
      if (call.<TransferNotificationDto>getArgument(0).transferId().equals(TRANSFER_ID)) {
        throw new IllegalStateException("SENSITIVE-OUTBOX");
      }
      return null;
    }).when(notification).publish(any(TransferNotificationDto.class));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(1);
    verify(transactionManager, times(1)).rollback(any());
    verify(transactionManager, times(1)).commit(any());
    assertThat(stored.get(OTHER_ID).status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(output.getAll()).contains(TRANSFER_ID, "IllegalStateException").doesNotContain("SENSITIVE");
  }

  @Test
  void aFailingListOfStaleIdsIsPropagated() {
    IllegalStateException failure = new IllegalStateException("query failed");
    lenient().when(externalRepository.findInProgressIdsCreatedBefore(any(Instant.class), anyInt()))
      .thenThrow(failure);

    assertThatThrownBy(() -> service.reconcileStale(THRESHOLD, MAX)).isSameAs(failure);

    verifyNoInteractions(clearingHouse, transactionManager, accountRepository, notification);
  }

  // ------------------------------------------------------------------ arguments and limits

  @Test
  void thresholdAndMaxArePassedToTheRepository() {
    Instant threshold = Instant.parse("2026-01-02T03:04:05Z");
    givenStaleIds();

    service.reconcileStale(threshold, 7);

    verify(externalRepository, times(1)).findInProgressIdsCreatedBefore(threshold, 7);
  }

  @Test
  void nothingStaleMeansNoQueryAndNoTransaction() {
    givenStaleIds();

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isZero();
    verify(externalRepository).findInProgressIdsCreatedBefore(THRESHOLD, MAX);
    verifyNoMoreInteractions(externalRepository);
    verifyNoInteractions(clearingHouse, transactionManager, accountRepository, notification);
  }

  @Test
  void invalidArgumentsAreRefused() {
    assertThatThrownBy(() -> service.reconcileStale(null, MAX)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> service.reconcileStale(THRESHOLD, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.reconcileStale(THRESHOLD, -3)).isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(externalRepository, clearingHouse, transactionManager, accountRepository, notification);
  }

  // ------------------------------------------------------------------ logs

  @Test
  void logsNeverContainIbansNorAmounts(CapturedOutput output) {
    String completedId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    String refundedId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    String notFoundId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    String failingId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    String concludedId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
    givenAccount("1000");
    givenStoredTransfer(completedId, TransferStatusEnum.SENT, "123.45");
    givenStoredTransfer(refundedId, TransferStatusEnum.UNKNOWN, "123.45");
    givenStoredTransfer(notFoundId, TransferStatusEnum.SENT, "123.45");
    givenStoredTransfer(failingId, TransferStatusEnum.SENT, "123.45");
    givenStoredTransfer(concludedId, TransferStatusEnum.COMPLETED, "123.45");
    givenStaleIds(completedId, refundedId, notFoundId, failingId, concludedId);
    givenStatus(completedId, accepted(completedId));
    givenStatus(refundedId, rejected(refundedId, "R"));
    givenNotFound(notFoundId);
    lenient().when(clearingHouse.queryStatus(failingId)).thenThrow(new IllegalStateException("SENSITIVE-ERROR"));
    givenStatus(concludedId, accepted(concludedId));

    int applied = service.reconcileStale(THRESHOLD, MAX);

    assertThat(applied).isEqualTo(3);
    String log = output.getAll();
    assertThat(log).contains(completedId, refundedId, notFoundId, failingId, concludedId);
    assertThat(log).contains("IllegalStateException");
    assertThat(log)
      .doesNotContain(SOURCE_IBAN)
      .doesNotContain(DEST_IBAN)
      .doesNotContain("123.45")
      .doesNotContain("SENSITIVE")
      .doesNotContain("Transfer[")
      .doesNotContain("Account[")
      .doesNotContain("ClearingOutcomeDto[");
  }
}
