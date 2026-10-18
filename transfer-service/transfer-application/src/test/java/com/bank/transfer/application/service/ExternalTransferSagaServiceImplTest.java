package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.ClearingHouseTimeoutException;
import com.bank.transfer.application.exception.ClearingHouseUnavailableException;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.application.port.in.CreateExternalTransferInPort;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.application.port.in.ProcessClearingOutcomesInPort;
import com.bank.transfer.application.port.in.ReconcileExternalTransfersInPort;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Saga of the external transfers: debit and SENT in one transaction, the clearing house outside of any
 * transaction, compensation only when the instruction was certainly not received, UNKNOWN otherwise.
 * The repositories are stateful (answers over maps), so a second transaction sees what the first one saved.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ExternalTransferSagaServiceImplTest {

  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final Iban SOURCE = new Iban(SOURCE_IBAN);
  private static final Iban DEST = new Iban(DEST_IBAN);
  private static final String UNAVAILABLE_REASON = "Clearing house unavailable";
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

  /** Accounts by IBAN value, as the database would hold them. */
  private Map<String, Account> accounts;

  /** External transfers by id, and the reason saved with each of them. */
  private Map<String, Transfer> stored;
  private Map<String, String> storedReasons;

  private ExternalTransferSagaServiceImpl service;

  @BeforeEach
  void setUp() {
    accounts = new HashMap<>();
    stored = new HashMap<>();
    storedReasons = new HashMap<>();

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
      storedReasons.put(transfer.id(), call.getArgument(1));
      return null;
    }).when(externalRepository).save(any(Transfer.class), any());
    lenient().when(externalRepository.findByIdForUpdate(anyString()))
      .thenAnswer(call -> Optional.ofNullable(stored.get(call.<String>getArgument(0))));

    service = new ExternalTransferSagaServiceImpl(accountRepository, externalRepository, fraudCheck, notification,
      clearingHouse, new TransactionTemplate(transactionManager));
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

  private Transfer givenStoredTransfer(String id, TransferStatusEnum status, String amount) {
    Transfer transfer = new Transfer(id, SOURCE, DEST, new Money(new BigDecimal(amount), "EUR"), status);
    stored.put(id, transfer);
    return transfer;
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

  /** Matches a transfer saved with the given status. */
  private static Transfer savedAs(TransferStatusEnum status) {
    return argThat(transfer -> transfer != null && transfer.status() == status);
  }

  private TransferNotificationDto publishedOnce() {
    ArgumentCaptor<TransferNotificationDto> published = ArgumentCaptor.forClass(TransferNotificationDto.class);
    verify(notification, times(1)).publish(published.capture());
    return published.getValue();
  }

  private ClearingInstructionDto submittedOnce() {
    ArgumentCaptor<ClearingInstructionDto> submitted = ArgumentCaptor.forClass(ClearingInstructionDto.class);
    verify(clearingHouse, times(1)).submit(submitted.capture());
    return submitted.getValue();
  }

  private void verifyNoAccountTouched() {
    verify(accountRepository, never()).findByIbanForUpdate(any(Iban.class));
    verify(accountRepository, never()).save(any(Account.class));
  }

  private BigDecimal balanceOfSource() {
    return accounts.get(SOURCE_IBAN).money().amount();
  }

  // ------------------------------------------------------------------ shape

  @Test
  void doesNotImplementTheCreateTransferInPort() {
    assertThat(CreateTransferInPort.class.isAssignableFrom(ExternalTransferSagaServiceImpl.class)).isFalse();
  }

  /**
   * Guard on the shape of the Saga: exactly the three in ports of the external flow (creation, clearing outcomes,
   * reconciliation); any other interface, including the routing and internal flow ports, makes it fail.
   */
  @Test
  void implementsOnlyTheThreeSagaInPorts() {
    assertThat(ExternalTransferSagaServiceImpl.class.getInterfaces())
      .containsExactlyInAnyOrder(CreateExternalTransferInPort.class, ProcessClearingOutcomesInPort.class,
        ReconcileExternalTransfersInPort.class);
  }

  // ------------------------------------------------------------------ steps 1-4: validation, antifraud

  @Test
  void missingSourceAccountIsRefusedWithoutAskingTheFraudCheck() {
    givenAccount(DEST_IBAN, "50");
    givenFraudDecision(FraudStatusEnum.REJECTED, "Amount must not exceed 10");

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30")))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage("Source Account not found");

    verifyNoInteractions(fraudCheck, externalRepository, clearingHouse, notification, transactionManager);
    verifyNoAccountTouched();
  }

  @Test
  void destinationIsNeverLookedUpAmongTheAccounts() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("SENT");
    verify(accountRepository).findByIban(SOURCE);
    verify(accountRepository, never()).findByIban(DEST);
    verify(accountRepository, never()).findByIbanForUpdate(DEST);
  }

  @Test
  void sameIbanOrThreeDecimalsAreRefusedBeforeTouchingAnything() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    String sameIbanFormattedDifferently = "it60 x054 2811 1010 0000 0000 001";

    assertThatThrownBy(() -> service.executeExternalTransfer(
      new CreateTransferCommand(SOURCE_IBAN, sameIbanFormattedDifferently, new BigDecimal("30"), "EUR")))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.executeExternalTransfer(command("1.234")))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Transfer amount can have at most 2 decimals");

    verifyNoInteractions(accountRepository, fraudCheck, externalRepository, clearingHouse, notification,
      transactionManager);
  }

  @Test
  void unavailableFraudCheckIsPropagatedAndNothingIsSaved() {
    givenAccount(SOURCE_IBAN, "100");
    FraudCheckUnavailableException unavailable = new FraudCheckUnavailableException(TRANSFER_ID);
    lenient().when(fraudCheck.checkFraud(anyString(), anyString(), anyString(), any(BigDecimal.class), anyString()))
      .thenThrow(unavailable);

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30"))).isSameAs(unavailable);

    verifyNoInteractions(externalRepository, clearingHouse, notification, transactionManager);
    verifyNoAccountTouched();
  }

  @Test
  void fraudRejectionSavesRejectedWithTheJoinedReasonsAndPublishesIt() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.REJECTED, "A", "B");

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    verify(externalRepository).save(savedAs(TransferStatusEnum.REJECTED), eq("A; B"));
    verify(externalRepository, times(1)).save(any(Transfer.class), any());
    TransferNotificationDto published = publishedOnce();
    assertThat(published.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(published.reasons()).containsExactly("A", "B");
    assertThat(published.transferId()).isEqualTo(result.transferId());
    verifyNoAccountTouched();
    verifyNoInteractions(clearingHouse);
    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(result.reasons()).containsExactly("A", "B");
    verify(transactionManager, times(1)).commit(any());
  }

  // ------------------------------------------------------------------ step 5: debit and SENT

  @Test
  void insufficientFundsRollsBackAndNeverSubmits() {
    givenAccount(SOURCE_IBAN, "10");
    givenFraudDecision(FraudStatusEnum.ALLOWED);

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30")))
      .isInstanceOf(InsufficientFundException.class);

    verify(accountRepository, never()).save(any(Account.class));
    verify(externalRepository, never()).save(any(Transfer.class), any());
    verifyNoInteractions(clearingHouse, notification);
    verify(transactionManager).rollback(any());
    verify(transactionManager, never()).commit(any());
  }

  @Test
  void differentCurrencyRollsBackAndNeverSubmits() {
    accounts.put(SOURCE_IBAN, new Account("acc-0001", SOURCE, new Money(new BigDecimal("100"), "USD")));
    givenFraudDecision(FraudStatusEnum.ALLOWED);

    assertThatThrownBy(() -> service.executeExternalTransfer(command("30")))
      .isInstanceOf(DifferentCurrencyException.class);

    verify(accountRepository, never()).save(any(Account.class));
    verify(externalRepository, never()).save(any(Transfer.class), any());
    verifyNoInteractions(clearingHouse, notification);
    verify(transactionManager).rollback(any());
    verify(transactionManager, never()).commit(any());
  }

  @Test
  void happyPathDebitsSavesSentCommitsAndOnlyThenSubmits() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    InOrder order = inOrder(fraudCheck, transactionManager, accountRepository, externalRepository, clearingHouse);
    order.verify(fraudCheck).checkFraud(anyString(), eq(SOURCE_IBAN), eq(DEST_IBAN), eq(new BigDecimal("30")),
      eq("EUR"));
    order.verify(transactionManager).getTransaction(any());
    order.verify(accountRepository).findByIbanForUpdate(SOURCE);
    order.verify(accountRepository).save(any(Account.class));
    order.verify(externalRepository).save(savedAs(TransferStatusEnum.SENT), isNull());
    order.verify(transactionManager).commit(any());
    order.verify(clearingHouse).submit(any(ClearingInstructionDto.class));

    assertThat(balanceOfSource()).isEqualByComparingTo("70");
    assertThat(result.transferStatus()).isEqualTo("SENT");
    assertThat(result.reasons()).isEmpty();
    assertThat(submittedOnce().transferId()).isEqualTo(result.transferId());
    assertThat(stored.get(result.transferId()).status()).isEqualTo(TransferStatusEnum.SENT);
    verifyNoInteractions(notification);
    verify(transactionManager, times(1)).commit(any());
  }

  @Test
  void submittedInstructionCarriesTheTransferData() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);

    TransferResultDto result = service.executeExternalTransfer(command("30.50"));

    ClearingInstructionDto instruction = submittedOnce();
    assertThat(instruction.transferId()).isEqualTo(result.transferId());
    assertThat(instruction.sourceIban()).isEqualTo(SOURCE_IBAN);
    assertThat(instruction.destIban()).isEqualTo(DEST_IBAN);
    assertThat(instruction.amount()).isEqualByComparingTo("30.50");
    assertThat(instruction.currency()).isEqualTo("EUR");
  }

  // ------------------------------------------------------------------ step 6: the four outcomes of submit

  @Test
  void unavailableClearingIsCompensatedAtOnce() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(ClearingHouseUnavailableException::new);

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(result.reasons()).containsExactly(UNAVAILABLE_REASON);
    InOrder order = inOrder(transactionManager, clearingHouse, externalRepository, accountRepository, notification);
    order.verify(clearingHouse).submit(any(ClearingInstructionDto.class));
    order.verify(externalRepository).findByIdForUpdate(result.transferId());
    order.verify(accountRepository).findByIbanForUpdate(SOURCE);
    order.verify(accountRepository).save(any(Account.class));
    order.verify(externalRepository).save(savedAs(TransferStatusEnum.REJECTED), eq(UNAVAILABLE_REASON));
    order.verify(notification).publish(any(TransferNotificationDto.class));
    order.verify(transactionManager).commit(any());
    assertThat(balanceOfSource()).isEqualByComparingTo("100");
    assertThat(stored.get(result.transferId()).status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(storedReasons.get(result.transferId())).isEqualTo(UNAVAILABLE_REASON);
    TransferNotificationDto published = publishedOnce();
    assertThat(published.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(published.reasons()).containsExactly(UNAVAILABLE_REASON);
    verify(transactionManager, times(2)).commit(any());
  }

  @Test
  void timeoutLeavesTheTransferUnknownWithoutCompensating() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(ClearingHouseTimeoutException::new);

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("UNKNOWN");
    assertThat(result.reasons()).isEmpty();
    verify(externalRepository).findByIdForUpdate(result.transferId());
    verify(externalRepository).save(savedAs(TransferStatusEnum.UNKNOWN), isNull());
    verify(externalRepository, never()).save(savedAs(TransferStatusEnum.REJECTED), any());
    // only the debit: no refund, no second lock on the account
    verify(accountRepository, times(1)).findByIbanForUpdate(any(Iban.class));
    verify(accountRepository, times(1)).save(any(Account.class));
    assertThat(balanceOfSource()).isEqualByComparingTo("70");
    assertThat(stored.get(result.transferId()).status()).isEqualTo(TransferStatusEnum.UNKNOWN);
    verifyNoInteractions(notification);
    verify(transactionManager, times(2)).commit(any());
  }

  @Test
  void unexpectedSubmitFailureIsTreatedAsUnknown() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(id -> new IllegalStateException("something went wrong"));

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("UNKNOWN");
    assertThat(result.reasons()).isEmpty();
    verify(externalRepository).save(savedAs(TransferStatusEnum.UNKNOWN), isNull());
    verify(accountRepository, times(1)).findByIbanForUpdate(any(Iban.class));
    verify(accountRepository, times(1)).save(any(Account.class));
    assertThat(balanceOfSource()).isEqualByComparingTo("70");
    verifyNoInteractions(notification);
    verify(transactionManager, times(2)).commit(any());
  }

  @Test
  void outcomeAppliedBeforeMarkUnknownIsKept() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    // the outcome of the clearing house is applied (by the scheduler) while submit is still waiting
    lenient().doAnswer(call -> {
      String id = call.<ClearingInstructionDto>getArgument(0).transferId();
      stored.put(id, stored.get(id).settle());
      throw new ClearingHouseTimeoutException(id);
    }).when(clearingHouse).submit(any(ClearingInstructionDto.class));

    TransferResultDto result = service.executeExternalTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("COMPLETED");
    assertThat(result.reasons()).isEmpty();
    // the only save is the one of SENT
    verify(externalRepository, times(1)).save(any(Transfer.class), any());
    verify(externalRepository, never()).save(savedAs(TransferStatusEnum.UNKNOWN), any());
    assertThat(stored.get(result.transferId()).status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(balanceOfSource()).isEqualByComparingTo("70");
    verify(transactionManager, times(2)).commit(any());
  }

  @Test
  void unknownTransferIsNeverNotified() {
    givenAccount(SOURCE_IBAN, "100");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    givenSubmitThrows(ClearingHouseTimeoutException::new);
    givenOutcomes();

    service.executeExternalTransfer(command("30"));
    int applied = service.processOutcomes();

    assertThat(applied).isZero();
    verify(notification, never()).publish(any(TransferNotificationDto.class));
  }

  // ------------------------------------------------------------------ processOutcomes

  @Test
  void acceptedOutcomeCompletesTheTransferAndPublishesCompleted() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenOutcomes(accepted(TRANSFER_ID));

    int applied = service.processOutcomes();

    assertThat(applied).isEqualTo(1);
    verify(externalRepository).save(savedAs(TransferStatusEnum.COMPLETED), isNull());
    TransferNotificationDto published = publishedOnce();
    assertThat(published.transferId()).isEqualTo(TRANSFER_ID);
    assertThat(published.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(published.reasons()).isEmpty();
    assertThat(published.sourceIban()).isEqualTo(SOURCE_IBAN);
    assertThat(published.destIban()).isEqualTo(DEST_IBAN);
    assertThat(published.amount()).isEqualByComparingTo("30");
    assertThat(published.currency()).isEqualTo("EUR");
    verifyNoAccountTouched();
    verify(transactionManager, times(1)).commit(any());
  }

  @Test
  void rejectedOutcomeRefundsTheSourceWithLocksInOrder() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenOutcomes(rejected(TRANSFER_ID, "R"));

    int applied = service.processOutcomes();

    assertThat(applied).isEqualTo(1);
    InOrder order = inOrder(externalRepository, accountRepository, notification);
    order.verify(externalRepository).findByIdForUpdate(TRANSFER_ID);
    order.verify(accountRepository).findByIbanForUpdate(SOURCE);
    order.verify(accountRepository).save(any(Account.class));
    order.verify(externalRepository).save(savedAs(TransferStatusEnum.REJECTED), eq("R"));
    order.verify(notification).publish(any(TransferNotificationDto.class));
    assertThat(balanceOfSource()).isEqualByComparingTo("100");
    TransferNotificationDto published = publishedOnce();
    assertThat(published.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(published.reasons()).containsExactly("R");
    assertThat(published.transferId()).isEqualTo(TRANSFER_ID);
    verify(transactionManager, times(1)).commit(any());
  }

  @Test
  void lateAcceptedOutcomeCompletesAnUnknownTransfer() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.UNKNOWN, "30");
    givenOutcomes(accepted(TRANSFER_ID));

    int applied = service.processOutcomes();

    assertThat(applied).isEqualTo(1);
    verify(externalRepository).save(savedAs(TransferStatusEnum.COMPLETED), isNull());
    assertThat(publishedOnce().status()).isEqualTo(TransferStatusEnum.COMPLETED);
    verifyNoAccountTouched();
    assertThat(balanceOfSource()).isEqualByComparingTo("70");
    assertThat(stored.get(TRANSFER_ID).status()).isEqualTo(TransferStatusEnum.COMPLETED);
  }

  @Test
  void lateRejectedOutcomeRefundsAnUnknownTransfer() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.UNKNOWN, "30");
    givenOutcomes(rejected(TRANSFER_ID, "R"));

    int applied = service.processOutcomes();

    assertThat(applied).isEqualTo(1);
    InOrder order = inOrder(externalRepository, accountRepository, notification);
    order.verify(externalRepository).findByIdForUpdate(TRANSFER_ID);
    order.verify(accountRepository).findByIbanForUpdate(SOURCE);
    order.verify(accountRepository).save(any(Account.class));
    order.verify(externalRepository).save(savedAs(TransferStatusEnum.REJECTED), eq("R"));
    order.verify(notification).publish(any(TransferNotificationDto.class));
    assertThat(balanceOfSource()).isEqualByComparingTo("100");
    TransferNotificationDto published = publishedOnce();
    assertThat(published.status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(published.reasons()).containsExactly("R");
  }

  @ParameterizedTest
  @EnumSource(value = TransferStatusEnum.class, names = {"COMPLETED", "REJECTED"})
  void outcomeForAnAlreadyConcludedTransferIsIgnored(TransferStatusEnum concluded) {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, concluded, "30");
    givenOutcomes(accepted(TRANSFER_ID), rejected(TRANSFER_ID, "R"));

    int applied = service.processOutcomes();

    assertThat(applied).isZero();
    verify(externalRepository, never()).save(any(Transfer.class), any());
    verifyNoAccountTouched();
    verifyNoInteractions(notification);
    assertThat(stored.get(TRANSFER_ID).status()).isEqualTo(concluded);
    assertThat(balanceOfSource()).isEqualByComparingTo("70");
  }

  @Test
  void sameRejectedOutcomeTwiceRefundsOnce() {
    givenAccount(SOURCE_IBAN, "70");
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    ClearingOutcomeDto rejection = rejected(TRANSFER_ID, "R");
    lenient().when(clearingHouse.pollOutcomes()).thenReturn(List.of(rejection, rejection), List.of(rejection));

    int firstRound = service.processOutcomes();
    int secondRound = service.processOutcomes();

    assertThat(firstRound).isEqualTo(1);
    assertThat(secondRound).isZero();
    assertThat(balanceOfSource()).isEqualByComparingTo("100");
    verify(accountRepository, times(1)).save(any(Account.class));
    verify(externalRepository, times(1)).save(savedAs(TransferStatusEnum.REJECTED), eq("R"));
    publishedOnce();
  }

  @Test
  void outcomeForAnUnknownTransferIsIgnored() {
    givenAccount(SOURCE_IBAN, "70");
    givenOutcomes(accepted(OTHER_ID), rejected(OTHER_ID, "R"));

    int applied = service.processOutcomes();

    assertThat(applied).isZero();
    verify(externalRepository, never()).save(any(Transfer.class), any());
    verifyNoAccountTouched();
    verifyNoInteractions(notification);
  }

  @Test
  void aFailingOutcomeIsRolledBackAndTheNextOneIsStillApplied() {
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
    verify(transactionManager, times(1)).rollback(any());
    verify(transactionManager, times(1)).commit(any());
    verify(notification, times(2)).publish(any(TransferNotificationDto.class));
    verify(externalRepository).save(savedAs(TransferStatusEnum.COMPLETED), isNull());
    assertThat(stored.get(OTHER_ID).status()).isEqualTo(TransferStatusEnum.COMPLETED);
  }

  /** Decision 9 of the plan: the refund of a vanished source account fails the outcome, not the whole round. */
  @Test
  void vanishedSourceAccountRollsBackTheRefundAndDoesNotStopTheRound(CapturedOutput output) {
    givenStoredTransfer(TRANSFER_ID, TransferStatusEnum.SENT, "30");
    givenOutcomes(rejected(TRANSFER_ID, "R"));

    int applied = service.processOutcomes();

    assertThat(applied).isZero();
    verify(transactionManager, times(1)).rollback(any());
    verify(transactionManager, never()).commit(any());
    verify(externalRepository, never()).save(savedAs(TransferStatusEnum.REJECTED), any());
    verifyNoInteractions(notification);
    assertThat(output.getAll()).doesNotContain(SOURCE_IBAN).doesNotContain(DEST_IBAN);
  }

  @Test
  void noOutcomesMeansNoTransaction() {
    givenOutcomes();

    int applied = service.processOutcomes();

    assertThat(applied).isZero();
    verifyNoInteractions(transactionManager, externalRepository, accountRepository, notification);
  }

  @Test
  void failingPollIsPropagated() {
    IllegalStateException failure = new IllegalStateException("poll failed");
    lenient().when(clearingHouse.pollOutcomes()).thenThrow(failure);

    assertThatThrownBy(() -> service.processOutcomes()).isSameAs(failure);

    verifyNoInteractions(transactionManager, externalRepository, accountRepository, notification);
  }

  // ------------------------------------------------------------------ logs

  @Test
  void logsNeverContainIbansNorAmounts(CapturedOutput output) {
    givenAccount(SOURCE_IBAN, "1000");
    givenFraudDecision(FraudStatusEnum.ALLOWED);
    CreateTransferCommand command = command("123.45");

    // SENT: the clearing house takes the instruction
    String sent = service.executeExternalTransfer(command).transferId();
    // compensation: the instruction was certainly not received
    givenSubmitThrows(ClearingHouseUnavailableException::new);
    String compensated = service.executeExternalTransfer(command).transferId();
    // UNKNOWN: timeout, then an unexpected error
    givenSubmitThrows(ClearingHouseTimeoutException::new);
    String timedOut = service.executeExternalTransfer(command).transferId();
    givenSubmitThrows(id -> new IllegalStateException("SENSITIVE-ERROR-MESSAGE"));
    String failed = service.executeExternalTransfer(command).transferId();
    assertThat(stored.get(sent).status()).isEqualTo(TransferStatusEnum.SENT);
    assertThat(stored.get(compensated).status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(stored.get(timedOut).status()).isEqualTo(TransferStatusEnum.UNKNOWN);
    assertThat(stored.get(failed).status()).isEqualTo(TransferStatusEnum.UNKNOWN);

    // outcomes: accepted, late rejected, duplicate, unknown id, and one that fails when it is applied
    lenient().doAnswer(call -> {
      if (call.<TransferNotificationDto>getArgument(0).transferId().equals(failed)) {
        throw new IllegalStateException("SENSITIVE-OUTBOX-MESSAGE");
      }
      return null;
    }).when(notification).publish(any(TransferNotificationDto.class));
    givenOutcomes(accepted(sent), rejected(timedOut, "R"), accepted(sent), accepted(OTHER_ID), accepted(failed));

    int applied = service.processOutcomes();

    assertThat(applied).isEqualTo(2);
    String log = output.getAll();
    assertThat(log).contains(sent, compensated, timedOut, failed, OTHER_ID);
    assertThat(log).containsIgnoringCase("unknown");
    assertThat(log).contains("ClearingHouseTimeoutException", "IllegalStateException");
    assertThat(log)
      .doesNotContain(SOURCE_IBAN)
      .doesNotContain(DEST_IBAN)
      .doesNotContain("123.45")
      .doesNotContain("SENSITIVE")
      .doesNotContain("Transfer[")
      .doesNotContain("Account[")
      .doesNotContain("ClearingInstructionDto[");
  }
}
