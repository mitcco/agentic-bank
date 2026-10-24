package com.bank.transfer.application.service;

import static com.bank.transfer.application.service.RecordingMetricsOutPort.requested;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.exception.ClearingHouseTimeoutException;
import com.bank.transfer.application.exception.ClearingHouseUnavailableException;
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
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Guasti della porta delle metriche nella Saga esterna (piano transfer-14, finding MAJOR del reviewer): la metrica
 * dopo un submit riuscito sta fuori dal try del submit, quindi un suo errore non trasforma in UNKNOWN un bonifico
 * gia ricevuto dalla stanza. Stesso setup di ExternalTransferSagaMetricsTest.
 */
@ExtendWith(MockitoExtension.class)
class ExternalTransferSagaMetricsFailureTest {

  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final Iban SOURCE = new Iban(SOURCE_IBAN);

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

    accounts.put(SOURCE_IBAN, new Account("acc-0001", SOURCE, new Money(new BigDecimal("100"), "EUR")));
    lenient().when(fraudCheck.checkFraud(anyString(), anyString(), anyString(), any(BigDecimal.class), anyString()))
      .thenReturn(new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of()));
  }

  private ExternalTransferSagaServiceImpl newService(RecordingMetricsOutPort metrics) {
    return new ExternalTransferSagaServiceImpl(accountRepository, externalRepository, fraudCheck, notification,
      clearingHouse, new TransactionTemplate(transactionManager), metrics);
  }

  private static CreateTransferCommand command() {
    return new CreateTransferCommand(SOURCE_IBAN, DEST_IBAN, new BigDecimal("30"), "EUR");
  }

  private long submitCalls() {
    return mockingDetails(clearingHouse).getInvocations().stream()
      .filter(invocation -> invocation.getMethod().getName().equals("submit")).count();
  }

  private Transfer onlyStoredTransfer() {
    assertThat(stored).hasSize(1);
    return stored.values().iterator().next();
  }

  /** Metrics port that records every call, then throws the given exception on requested(EXTERNAL, SENT). */
  private static RecordingMetricsOutPort failingOnSentRequest(RuntimeException failure) {
    return new RecordingMetricsOutPort() {
      @Override
      public void transferRequested(TransferTypeEnum type, TransferStatusEnum status) {
        super.transferRequested(type, status);
        if (type == TransferTypeEnum.EXTERNAL && status == TransferStatusEnum.SENT) {
          throw failure;
        }
      }
    };
  }

  @Test
  void metricsFailureAfterAcceptedSubmitPropagatesAndTheTransferStaysSent() {
    RuntimeException failure = new IllegalStateException("metrics registry down");
    RecordingMetricsOutPort metrics = failingOnSentRequest(failure);

    assertThatThrownBy(() -> newService(metrics).executeExternalTransfer(command()))
      .isSameAs(failure)
      .hasMessage("metrics registry down");

    assertThat(onlyStoredTransfer().status()).isEqualTo(TransferStatusEnum.SENT);
    assertThat(metrics.calls()).containsExactly(requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.SENT));
    assertThat(submitCalls()).isEqualTo(1);
    // debited once (100 - 30), never refunded
    assertThat(accounts.get(SOURCE_IBAN).money().amount()).isEqualByComparingTo("70");
  }

  @Test
  void timeoutOnSubmitWithWorkingMetricsStillLeavesUnknownAndCountsUnknown() {
    lenient().doAnswer(call -> {
      throw new ClearingHouseTimeoutException(call.<ClearingInstructionDto>getArgument(0).transferId());
    }).when(clearingHouse).submit(any(ClearingInstructionDto.class));
    RecordingMetricsOutPort metrics = new RecordingMetricsOutPort();

    TransferResultDto result = newService(metrics).executeExternalTransfer(command());

    assertThat(result.transferStatus()).isEqualTo("UNKNOWN");
    assertThat(onlyStoredTransfer().status()).isEqualTo(TransferStatusEnum.UNKNOWN);
    assertThat(metrics.calls()).containsExactly(requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.UNKNOWN));
    assertThat(accounts.get(SOURCE_IBAN).money().amount()).isEqualByComparingTo("70");
  }

  @Test
  void immediateCompensationWithOutcomeAlreadyAppliedCountsOnlyTheRejectedRequest() {
    lenient().doAnswer(call -> {
      String id = call.<ClearingInstructionDto>getArgument(0).transferId();
      // a concurrent outcome concludes the transfer before the compensation takes the lock
      stored.put(id, stored.get(id).settle());
      throw new ClearingHouseUnavailableException(id);
    }).when(clearingHouse).submit(any(ClearingInstructionDto.class));
    RecordingMetricsOutPort metrics = new RecordingMetricsOutPort();

    TransferResultDto result = newService(metrics).executeExternalTransfer(command());

    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(onlyStoredTransfer().status()).isEqualTo(TransferStatusEnum.COMPLETED);
    assertThat(metrics.calls()).containsExactly(requested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED));
    // no refund was applied: the debit stays
    assertThat(accounts.get(SOURCE_IBAN).money().amount()).isEqualByComparingTo("70");
  }
}
