package com.bank.transfer.application.service;

import static com.bank.transfer.application.service.RecordingMetricsOutPort.concluded;
import static com.bank.transfer.application.service.RecordingMetricsOutPort.requested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.NotificationOutPort;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Metriche del flusso interno (piano transfer-14, D1, D9, D11): si contano dopo il commit, una volta per richiesta
 * riuscita; un'eccezione con rollback non lascia nessun conteggio. Stesso setup Mockito di
 * CreateTransferServiceImplTest, con il fake RecordingMetricsOutPort.
 */
@ExtendWith(MockitoExtension.class)
class CreateTransferServiceMetricsTest {

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
  private NotificationOutPort notification;

  @Mock
  private PlatformTransactionManager transactionManager;

  private RecordingMetricsOutPort metrics;
  private CreateTransferServiceImpl service;

  @BeforeEach
  void setUp() {
    metrics = new RecordingMetricsOutPort();
    service = new CreateTransferServiceImpl(accountRepository, transferRepository, fraudCheck, notification,
      new TransactionTemplate(transactionManager), metrics);
  }

  private static Account account(String id, Iban iban, String amount, String currency) {
    return new Account(id, iban, new Money(new BigDecimal(amount), currency));
  }

  private static CreateTransferCommand command(String amount) {
    return new CreateTransferCommand(SOURCE_IBAN, DEST_IBAN, new BigDecimal(amount), "EUR");
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

  private boolean committed() {
    return mockingDetails(transactionManager).getInvocations().stream()
      .anyMatch(invocation -> invocation.getMethod().getName().equals("commit"));
  }

  // ------------------------------------------------------------------ esiti contati

  @Test
  void allowedTransferCountsOneRequestAndOneConclusionAsCompleted() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    TransferResultDto result = service.executeTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("COMPLETED");
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      requested(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED),
      concluded(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED));
  }

  @Test
  void fraudRejectedTransferCountsRequestAndConclusionAsRejectedAndNoRefund() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.REJECTED, List.of("Amount must not exceed 10000 EUR"));

    TransferResultDto result = service.executeTransfer(command("30"));

    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      requested(TransferTypeEnum.INTERNAL, TransferStatusEnum.REJECTED),
      concluded(TransferTypeEnum.INTERNAL, TransferStatusEnum.REJECTED));
    assertThat(metrics.calls()).noneMatch(call -> call.startsWith("refunded") || call.startsWith("reconciliation"));
  }

  @Test
  void allowedWithSpuriousReasonsIsCountedAsCompleted() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of("x"));

    service.executeTransfer(command("30"));

    assertThat(metrics.calls()).containsExactlyInAnyOrder(
      requested(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED),
      concluded(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED));
  }

  @Test
  void twoTransfersAreCountedTwiceEach() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    service.executeTransfer(command("10"));
    service.executeTransfer(command("20"));

    assertThat(metrics.calls()).hasSize(4);
    assertThat(metrics.calls().stream()
      .filter(call -> call.equals(requested(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED))).count())
      .isEqualTo(2);
  }

  // ------------------------------------------------------------------ dopo il commit (D11)

  @Test
  void metricsAreCalledOnlyAfterTheCommit() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());
    List<Boolean> committedAtCallTime = new ArrayList<>();
    RecordingMetricsOutPort probing = new RecordingMetricsOutPort() {
      @Override
      public void transferRequested(TransferTypeEnum type, TransferStatusEnum status) {
        committedAtCallTime.add(committed());
        super.transferRequested(type, status);
      }

      @Override
      public void transferConcluded(TransferTypeEnum type, TransferStatusEnum status) {
        committedAtCallTime.add(committed());
        super.transferConcluded(type, status);
      }
    };
    CreateTransferServiceImpl probed = new CreateTransferServiceImpl(accountRepository, transferRepository,
      fraudCheck, notification, new TransactionTemplate(transactionManager), probing);

    probed.executeTransfer(command("30"));

    assertThat(committedAtCallTime).containsExactly(true, true);
  }

  @Test
  void metricsAreCalledAfterTheCommitAlsoForARejectedTransfer() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.REJECTED, List.of("R"));
    List<Boolean> committedAtCallTime = new ArrayList<>();
    RecordingMetricsOutPort probing = new RecordingMetricsOutPort() {
      @Override
      public void transferConcluded(TransferTypeEnum type, TransferStatusEnum status) {
        committedAtCallTime.add(committed());
        super.transferConcluded(type, status);
      }
    };
    CreateTransferServiceImpl probed = new CreateTransferServiceImpl(accountRepository, transferRepository,
      fraudCheck, notification, new TransactionTemplate(transactionManager), probing);

    probed.executeTransfer(command("30"));

    assertThat(committedAtCallTime).containsExactly(true);
  }

  // ------------------------------------------------------------------ nessun conteggio con eccezione (D10, D11)

  @Test
  void missingSourceAccountThrowsAndNothingIsCounted() {
    givenAccounts(account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    assertThatThrownBy(() -> service.executeTransfer(command("30"))).isInstanceOf(AccountNotFoundException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void missingDestinationAccountThrowsAndNothingIsCounted() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    assertThatThrownBy(() -> service.executeTransfer(command("30"))).isInstanceOf(AccountNotFoundException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void unavailableFraudCheckThrowsAndNothingIsCounted() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    lenient().when(fraudCheck.checkFraud(anyString(), anyString(), anyString(), any(BigDecimal.class), anyString()))
      .thenThrow(new FraudCheckUnavailableException(ID));

    assertThatThrownBy(() -> service.executeTransfer(command("30")))
      .isInstanceOf(FraudCheckUnavailableException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void insufficientFundsRollsBackAndNothingIsCounted() {
    givenAccounts(account("acc-1", SOURCE, "10", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    assertThatThrownBy(() -> service.executeTransfer(command("30"))).isInstanceOf(InsufficientFundException.class);

    verify(transactionManager).rollback(any());
    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void fundsEqualToTheAmountAreEnoughAndTheTransferIsCounted() {
    givenAccounts(account("acc-1", SOURCE, "30", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    service.executeTransfer(command("30"));

    assertThat(metrics.calls()).contains(requested(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED));
  }

  @Test
  void differentCurrencyRollsBackAndNothingIsCounted() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "USD"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());

    assertThatThrownBy(() -> service.executeTransfer(command("30"))).isInstanceOf(DifferentCurrencyException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void failingSaveOfTheTransferRollsBackAndNothingIsCounted() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());
    RuntimeException failure = new IllegalStateException("database down");
    doThrow(failure).when(transferRepository).save(any(Transfer.class));

    assertThatThrownBy(() -> service.executeTransfer(command("30"))).isSameAs(failure);

    verify(transactionManager).rollback(any());
    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void failingSaveOfARejectedTransferRollsBackAndNothingIsCounted() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.REJECTED, List.of("R"));
    RuntimeException failure = new IllegalStateException("database down");
    doThrow(failure).when(transferRepository).save(any(Transfer.class));

    assertThatThrownBy(() -> service.executeTransfer(command("30"))).isSameAs(failure);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void failingOutboxWriteRollsBackAndNothingIsCounted() {
    givenAccounts(account("acc-1", SOURCE, "100", "EUR"), account("acc-2", DEST, "50", "EUR"));
    givenFraudDecision(FraudStatusEnum.ALLOWED, List.of());
    RuntimeException failure = new IllegalStateException("outbox write failed");
    doThrow(failure).when(notification).publish(any(TransferNotificationDto.class));

    assertThatThrownBy(() -> service.executeTransfer(command("30"))).isSameAs(failure);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void invalidInputIsRefusedBeforeAnythingAndNothingIsCounted() {
    assertThatThrownBy(() -> service.executeTransfer(command("1.234"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.executeTransfer(
      new CreateTransferCommand(SOURCE_IBAN, "it60 x054 2811 1010 0000 0000 001", new BigDecimal("30"), "EUR")))
      .isInstanceOf(IllegalArgumentException.class);

    assertThat(metrics.calls()).isEmpty();
  }
}
