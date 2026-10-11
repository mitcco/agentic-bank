package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.TransferRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import com.bank.transfer.domain.exception.DifferentCurrencyException;
import com.bank.transfer.domain.exception.InsufficientFundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class CreateTransferServiceImplTest {

  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0542811101000000000002";
  private static final Iban SOURCE = new Iban(SOURCE_IBAN);
  private static final Iban DEST = new Iban(DEST_IBAN);
  private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";

  @Mock
  private AccountRepositoryOutPort accountRepository;

  @Mock
  private TransferRepositoryOutPort transferRepository;

  @Mock
  private FraudCheckOutPort fraudCheck;

  @Mock
  private PlatformTransactionManager transactionManager;

  private CreateTransferServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new CreateTransferServiceImpl(accountRepository, transferRepository, fraudCheck,
      new TransactionTemplate(transactionManager));
  }

  private static Account account(String id, Iban iban, String amount, String currency) {
    return new Account(id, iban, new Money(new BigDecimal(amount), currency));
  }

  private static CreateTransferCommand command(String source, String dest, String amount) {
    return new CreateTransferCommand(source, dest, new BigDecimal(amount), "EUR");
  }

  private void givenAccounts(Account... accounts) {
    for (Account account : accounts) {
      lenient().when(accountRepository.findByIban(account.iban())).thenReturn(Optional.of(account));
      lenient().when(accountRepository.findByIbanForUpdate(account.iban())).thenReturn(Optional.of(account));
    }
  }

  private void givenFraudDecision(FraudStatusEnum decision, List<String> reasons) {
    lenient().when(fraudCheck.checkFraud(anyString(), anyString(), anyString(), any(BigDecimal.class), anyString()))
      .thenReturn(new FraudCheckResultDto(decision, reasons));
  }

  @Test
  void validTransferMovesTheMoneyAndSavesEverythingAfterTheFraudCheck() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    TransferResultDto result = service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30"));

    ArgumentCaptor<Account> savedAccounts = ArgumentCaptor.forClass(Account.class);
    verify(accountRepository, times(2)).save(savedAccounts.capture());
    assertThat(savedAccounts.getAllValues()).containsExactlyInAnyOrder(
      account("acc-1", SOURCE, "70", "EUR"),
      account("acc-2", DEST, "80", "EUR"));

    ArgumentCaptor<Transfer> savedTransfer = ArgumentCaptor.forClass(Transfer.class);
    verify(transferRepository).save(savedTransfer.capture());
    assertThat(savedTransfer.getValue().status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(result.transferId()).isEqualTo(savedTransfer.getValue().id());
    assertThat(result.transferStatus()).isEqualTo("COMPLETED");

    verify(transactionManager).commit(any());
  }

  @Test
  void fraudCheckIsAskedBeforeAnySaveAndTheAccountsAreLockedBeforeBeingSaved() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30"));

    InOrder order = inOrder(fraudCheck, accountRepository, transferRepository);
    order.verify(fraudCheck).checkFraud(anyString(), eq(SOURCE_IBAN), eq(DEST_IBAN), eq(new BigDecimal("30")), eq("EUR"));
    order.verify(accountRepository).findByIbanForUpdate(SOURCE);
    order.verify(accountRepository).findByIbanForUpdate(DEST);
    order.verify(accountRepository, times(2)).save(any(Account.class));
    order.verify(transferRepository).save(any(Transfer.class));
  }

  @Test
  void accountsAreLockedInIbanOrderWhateverTheDirectionOfTheTransfer() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    // the source is the greater IBAN, but the smaller one has to be locked first
    service.executeTransfer(command(DEST_IBAN, SOURCE_IBAN, "30"));

    InOrder order = inOrder(accountRepository);
    order.verify(accountRepository).findByIbanForUpdate(SOURCE);
    order.verify(accountRepository).findByIbanForUpdate(DEST);
  }

  @Test
  void rejectedTransferIsSavedAsRejectedAndTheAccountsAreNotTouched() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.REJECTED, List.of("Amount must not exceed 10"));

    TransferResultDto result = service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30"));

    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(result.reasons()).containsExactly("Amount must not exceed 10");
    ArgumentCaptor<Transfer> savedTransfer = ArgumentCaptor.forClass(Transfer.class);
    verify(transferRepository).save(savedTransfer.capture());
    assertThat(savedTransfer.getValue().status()).isEqualTo(TransferStatusEnum.REJECTED);
    verify(accountRepository, never()).save(any(Account.class));
    verify(accountRepository, never()).findByIbanForUpdate(any(Iban.class));
    verify(transactionManager).commit(any());
  }

  @Test
  void insufficientFundsSavesNothingAndRollsBack() {
    givenAccounts(account("acc-1", SOURCE, "10", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    assertThatThrownBy(() -> service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30")))
      .isInstanceOf(InsufficientFundException.class);

    verify(accountRepository, never()).save(any(Account.class));
    verify(transferRepository, never()).save(any(Transfer.class));
    verify(transactionManager).rollback(any());
    verify(transactionManager, never()).commit(any());
  }

  @Test
  void differentCurrencyOnTheDestinationSavesNothingNotEvenTheSource() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "USD"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    assertThatThrownBy(() -> service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30")))
      .isInstanceOf(DifferentCurrencyException.class);

    verify(accountRepository, never()).save(any(Account.class));
    verify(transferRepository, never()).save(any(Transfer.class));
    verify(transactionManager).rollback(any());
  }

  @Test
  void sameIbanIsRefusedBeforeTouchingAnything() {
    String sameIbanFormattedDifferently = "it60 x054 2811 1010 0000 0000 001";

    assertThatThrownBy(() -> service.executeTransfer(command(SOURCE_IBAN, sameIbanFormattedDifferently, "30")))
      .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(accountRepository, fraudCheck, transferRepository, transactionManager);
  }

  @Test
  void missingSourceAccountIsRefusedWithoutAskingTheFraudCheck() {
    givenAccounts(account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.REJECTED, List.of("Amount must not exceed 10"));

    assertThatThrownBy(() -> service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30")))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage("Source Account not found");

    verifyNoInteractions(fraudCheck, transferRepository, transactionManager);
    verify(accountRepository, never()).save(any(Account.class));
  }

  @Test
  void missingDestinationAccountIsRefusedWithoutAskingTheFraudCheck() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"));
    givenFraudDecision(FraudStatusEnum.REJECTED, List.of("Amount must not exceed 10"));

    assertThatThrownBy(() -> service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30")))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage("Destination Account not found");

    verifyNoInteractions(fraudCheck, transferRepository, transactionManager);
    verify(accountRepository, never()).save(any(Account.class));
  }

  @Test
  void transferIdSentToTheFraudCheckIsTheOneOfTheSavedTransferAndOfTheResult() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    TransferResultDto result = service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30"));

    ArgumentCaptor<String> sentTransferId = ArgumentCaptor.forClass(String.class);
    verify(fraudCheck).checkFraud(sentTransferId.capture(), anyString(), anyString(), any(BigDecimal.class), anyString());
    ArgumentCaptor<Transfer> savedTransfer = ArgumentCaptor.forClass(Transfer.class);
    verify(transferRepository).save(savedTransfer.capture());
    assertThat(sentTransferId.getValue()).isNotBlank();
    assertThat(savedTransfer.getValue().id()).isEqualTo(sentTransferId.getValue());
    assertThat(result.transferId()).isEqualTo(sentTransferId.getValue());
    assertThat(result.reasons()).isEmpty();
  }

  @Test
  void rejectedTransferReturnsAllTheReasonsInOrderAndLeavesTheAccountsUntouched() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.REJECTED, List.of("Amount must not exceed 10000 EUR", "Source IBAN is locked"));

    TransferResultDto result = service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30"));

    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(result.reasons()).containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
    ArgumentCaptor<Transfer> savedTransfer = ArgumentCaptor.forClass(Transfer.class);
    verify(transferRepository).save(savedTransfer.capture());
    assertThat(savedTransfer.getValue().status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(result.transferId()).isEqualTo(savedTransfer.getValue().id());
    verify(accountRepository, never()).save(any(Account.class));
    verify(accountRepository, never()).findByIbanForUpdate(any(Iban.class));
  }

  @Test
  void unavailableFraudCheckIsPropagatedAndNothingIsLockedSavedOrStartedInATransaction() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    FraudCheckUnavailableException unavailable = new FraudCheckUnavailableException(ID);
    lenient().when(fraudCheck.checkFraud(anyString(), anyString(), anyString(), any(BigDecimal.class), anyString()))
      .thenThrow(unavailable);

    assertThatThrownBy(() -> service.executeTransfer(command(SOURCE_IBAN, DEST_IBAN, "30")))
      .isSameAs(unavailable);

    verifyNoInteractions(transferRepository, transactionManager);
    verify(accountRepository, never()).save(any(Account.class));
    verify(accountRepository, never()).findByIbanForUpdate(any(Iban.class));
  }

  @Test
  void unavailableExceptionMessageHasTheTransferIdButNoIbanNorAmountAndNoCause() {
    FraudCheckUnavailableException exception = new FraudCheckUnavailableException(ID);

    assertThat(exception).isInstanceOf(RuntimeException.class).isNotInstanceOf(IllegalArgumentException.class);
    assertThat(exception.getMessage()).contains(ID).doesNotContain(SOURCE_IBAN).doesNotContain(DEST_IBAN);
    assertThat(exception.transferId()).isEqualTo(ID);
    assertThat(exception.getCause()).isNull();
  }
}
