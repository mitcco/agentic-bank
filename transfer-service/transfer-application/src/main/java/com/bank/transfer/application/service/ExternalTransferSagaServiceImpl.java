package com.bank.transfer.application.service;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.ReconciliationOutcomeEnum;
import com.bank.transfer.application.dto.RefundCauseEnum;
import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.ClearingHouseUnavailableException;
import com.bank.transfer.application.port.in.CreateExternalTransferInPort;
import com.bank.transfer.application.port.in.ProcessClearingOutcomesInPort;
import com.bank.transfer.application.port.in.ReconcileExternalTransfersInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.ClearingHouseOutPort;
import com.bank.transfer.application.port.out.ExternalTransferRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.MetricsOutPort;
import com.bank.transfer.application.port.out.NotificationOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saga of the external transfers: debit and SENT in one transaction, then the clearing house outside of any
 * transaction. The debit is compensated only when the instruction was certainly not received
 * ({@link ClearingHouseUnavailableException}); any other failure of the submission leaves the transfer UNKNOWN,
 * with the funds still debited. It stays UNKNOWN until a late outcome arrives (the poll of
 * {@link ProcessClearingOutcomesInPort}) or until the reconciliation ({@code reconcileStale} of
 * {@link ReconcileExternalTransfersInPort}) asks the clearing house about it.
 * Implements three in ports: create, process the outcomes, reconcile the stale transfers.
 * Logs carry only the transfer id, the status and the simple name of an exception.
 */
@Service
public class ExternalTransferSagaServiceImpl
  implements CreateExternalTransferInPort, ProcessClearingOutcomesInPort, ReconcileExternalTransfersInPort {

  private static final Logger LOG = LoggerFactory.getLogger(ExternalTransferSagaServiceImpl.class);

  private static final String CLEARING_UNAVAILABLE_REASON = "Clearing house unavailable";
  private static final String INSTRUCTION_NOT_FOUND_REASON = "Instruction not found at the clearing house";
  private static final String SOURCE_NOT_FOUND = "Source Account not found";

  private final AccountRepositoryOutPort accountRepositoryOutPort;
  private final ExternalTransferRepositoryOutPort externalTransferRepositoryOutPort;
  private final FraudCheckOutPort fraudCheckOutPort;
  private final NotificationOutPort notificationOutPort;
  private final ClearingHouseOutPort clearingHouseOutPort;
  private final TransactionTemplate transactionTemplate;
  private final MetricsOutPort metricsOutPort;

  public ExternalTransferSagaServiceImpl(AccountRepositoryOutPort accountRepositoryOutPort,
    ExternalTransferRepositoryOutPort externalTransferRepositoryOutPort, FraudCheckOutPort fraudCheckOutPort,
    NotificationOutPort notificationOutPort, ClearingHouseOutPort clearingHouseOutPort,
    TransactionTemplate transactionTemplate, MetricsOutPort metricsOutPort) {
    this.metricsOutPort = Objects.requireNonNull(metricsOutPort, "metricsOutPort");
    this.accountRepositoryOutPort = accountRepositoryOutPort;
    this.externalTransferRepositoryOutPort = externalTransferRepositoryOutPort;
    this.fraudCheckOutPort = fraudCheckOutPort;
    this.notificationOutPort = notificationOutPort;
    this.clearingHouseOutPort = clearingHouseOutPort;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public TransferResultDto executeExternalTransfer(CreateTransferCommand command) {
    Iban source = new Iban(command.sourceIban());
    Iban dest = new Iban(command.destIban());
    Money money = new Money(command.amount(), command.currency());
    Transfer transfer = Transfer.create(source, dest, money);

    if (accountRepositoryOutPort.findByIban(source).isEmpty()) {
      throw new AccountNotFoundException(SOURCE_NOT_FOUND);
    }

    FraudCheckResultDto fraudCheckResultDto =
      fraudCheckOutPort.checkFraud(transfer.id(), source.value(), dest.value(), money.amount(), money.currency());

    if (!FraudStatusEnum.ALLOWED.equals(fraudCheckResultDto.decision())) {
      TransferResultDto rejectedResult = transactionTemplate.execute(status -> {
        Transfer rejected = transfer.reject();
        externalTransferRepositoryOutPort.save(rejected, String.join("; ", fraudCheckResultDto.reasons()));
        notificationOutPort.publish(toNotification(rejected, fraudCheckResultDto.reasons()));
        return new TransferResultDto(rejected.id(), rejected.status().name(), fraudCheckResultDto.reasons());
      });
      metricsOutPort.transferRequested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED);
      metricsOutPort.transferConcluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED);
      return rejectedResult;
    }

    Transfer sent = transactionTemplate.execute(status -> {
      Account account = accountRepositoryOutPort.findByIbanForUpdate(source)
                          .orElseThrow(() -> new AccountNotFoundException(SOURCE_NOT_FOUND));
      accountRepositoryOutPort.save(account.charge(money));
      Transfer markedSent = transfer.markSent();
      externalTransferRepositoryOutPort.save(markedSent, null);
      return markedSent;
    });
    LOG.info("External transfer {} SENT", sent.id());

    return submitToClearingHouse(sent);
  }

  private TransferResultDto submitToClearingHouse(Transfer sent) {
    ClearingInstructionDto instruction = new ClearingInstructionDto(sent.id(), sent.source().value(),
      sent.dest().value(), sent.money().amount(), sent.money().currency());
    try {
      clearingHouseOutPort.submit(instruction);
    } catch (ClearingHouseUnavailableException e) {
      LOG.warn("Instruction not received for transfer {}: {}", sent.id(), e.getClass().getSimpleName());
      applyOutcome(new ClearingOutcomeDto(sent.id(), ClearingDecisionEnum.REJECTED, CLEARING_UNAVAILABLE_REASON),
        RefundCauseEnum.CLEARING_UNAVAILABLE)
        .ifPresent(concluded -> recordConclusion(concluded, RefundCauseEnum.CLEARING_UNAVAILABLE));
      // the client always gets REJECTED here, even if a concurrent outcome had already been applied
      metricsOutPort.transferRequested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED);
      return new TransferResultDto(sent.id(), TransferStatusEnum.REJECTED.name(),
        List.of(CLEARING_UNAVAILABLE_REASON));
    } catch (RuntimeException e) {
      LOG.warn("Clearing house outcome unknown for transfer {}: {}", sent.id(), e.getClass().getSimpleName());
      String status = markUnknownIfSent(sent.id());
      metricsOutPort.transferRequested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.valueOf(status));
      return new TransferResultDto(sent.id(), status, List.of());
    }
    metricsOutPort.transferRequested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.SENT);
    return new TransferResultDto(sent.id(), sent.status().name(), List.of());
  }

  private String markUnknownIfSent(String transferId) {
    return transactionTemplate.execute(status -> {
      Transfer current = externalTransferRepositoryOutPort.findByIdForUpdate(transferId)
                           .orElseThrow(() -> new IllegalStateException("External transfer not found"));
      if (current.status() != TransferStatusEnum.SENT) {
        LOG.info("Transfer {} already concluded: {}", transferId, current.status());
        return current.status().name();
      }
      Transfer unknown = current.markUnknown();
      externalTransferRepositoryOutPort.save(unknown, null);
      LOG.info("External transfer {} {}", transferId, unknown.status());
      return unknown.status().name();
    });
  }

  @Override
  public int processOutcomes() {
    List<ClearingOutcomeDto> outcomes = clearingHouseOutPort.pollOutcomes();
    int applied = 0;
    for (ClearingOutcomeDto outcome : outcomes) {
      Optional<TransferStatusEnum> concluded;
      try {
        concluded = applyOutcome(outcome, RefundCauseEnum.CLEARING_REJECTED);
      } catch (RuntimeException e) {
        LOG.warn("Outcome failed for transfer {}: {}", outcome.transferId(), e.getClass().getSimpleName());
        continue;
      }
      // metrics outside the try: a failure of the metrics port must not be swallowed as an outcome failure
      if (concluded.isPresent()) {
        recordConclusion(concluded.get(), RefundCauseEnum.CLEARING_REJECTED);
        applied++;
      }
    }
    return applied;
  }

  @Override
  public int reconcileStale(Instant createdBefore, int maxTransfers) {
    Objects.requireNonNull(createdBefore, "createdBefore");
    if (maxTransfers < 1) {
      throw new IllegalArgumentException("maxTransfers must be at least 1");
    }
    List<String> ids = externalTransferRepositoryOutPort.findInProgressIdsCreatedBefore(createdBefore, maxTransfers);
    int applied = 0;
    for (String id : ids) {
      boolean notFound;
      RefundCauseEnum cause;
      Optional<TransferStatusEnum> concluded;
      try {
        Optional<ClearingOutcomeDto> queried = clearingHouseOutPort.queryStatus(id);
        notFound = queried.isEmpty();
        cause = notFound ? RefundCauseEnum.NOT_FOUND_AT_CLEARING : RefundCauseEnum.CLEARING_REJECTED;
        ClearingOutcomeDto outcome = queried.orElseGet(() -> {
          LOG.info("Transfer {} not found at the clearing house", id);
          return new ClearingOutcomeDto(id, ClearingDecisionEnum.REJECTED, INSTRUCTION_NOT_FOUND_REASON);
        });
        concluded = applyOutcome(outcome, cause);
      } catch (RuntimeException e) {
        LOG.warn("Reconciliation failed for transfer {}: {}", id, e.getClass().getSimpleName());
        metricsOutPort.reconciliationExamined(ReconciliationOutcomeEnum.FAILED);
        continue;
      }
      // metrics outside the try: a failure of the metrics port must not be swallowed as a reconciliation failure
      if (concluded.isPresent()) {
        recordConclusion(concluded.get(), cause);
        metricsOutPort.reconciliationExamined(notFound ? ReconciliationOutcomeEnum.NOT_FOUND
          : ReconciliationOutcomeEnum.valueOf(concluded.get().name()));
        applied++;
      } else {
        metricsOutPort.reconciliationExamined(ReconciliationOutcomeEnum.SKIPPED);
      }
    }
    return applied;
  }

  private void recordConclusion(TransferStatusEnum concluded, RefundCauseEnum refundCause) {
    metricsOutPort.transferConcluded(TransferTypeEnum.EXTERNAL, concluded);
    if (concluded == TransferStatusEnum.REJECTED) {
      metricsOutPort.transferRefunded(refundCause);
    }
  }

  /** Empty when the outcome was ignored; otherwise the status reached. The refund cause is carried by the caller. */
  private Optional<TransferStatusEnum> applyOutcome(ClearingOutcomeDto outcome, RefundCauseEnum refundCause) {
    return Optional.ofNullable(transactionTemplate.execute(status -> {
      Optional<Transfer> found = externalTransferRepositoryOutPort.findByIdForUpdate(outcome.transferId());
      if (found.isEmpty()) {
        LOG.warn("Outcome for unknown transfer {}", outcome.transferId());
        return null;
      }
      Transfer transfer = found.get();
      if (transfer.status() != TransferStatusEnum.SENT && transfer.status() != TransferStatusEnum.UNKNOWN) {
        LOG.info("Duplicate outcome ignored for transfer {}: {}", transfer.id(), transfer.status());
        return null;
      }

      Transfer concluded;
      if (outcome.decision() == ClearingDecisionEnum.ACCEPTED) {
        concluded = transfer.settle();
        externalTransferRepositoryOutPort.save(concluded, null);
        notificationOutPort.publish(toNotification(concluded, List.of()));
      } else {
        Account account = accountRepositoryOutPort.findByIbanForUpdate(transfer.source())
                            .orElseThrow(() -> new IllegalStateException(SOURCE_NOT_FOUND));
        accountRepositoryOutPort.save(account.credit(transfer.money()));
        concluded = transfer.rejectAfterSent();
        externalTransferRepositoryOutPort.save(concluded, outcome.reason());
        notificationOutPort.publish(toNotification(concluded, List.of(outcome.reason())));
      }
      LOG.info("Outcome applied to transfer {}: {}", concluded.id(), concluded.status());
      return concluded.status();
    }));
  }

  private static TransferNotificationDto toNotification(Transfer concluded, List<String> reasons) {
    return new TransferNotificationDto(concluded.id(), concluded.source().value(), concluded.dest().value(),
      concluded.money().amount(), concluded.money().currency(), concluded.status(), reasons);
  }
}
