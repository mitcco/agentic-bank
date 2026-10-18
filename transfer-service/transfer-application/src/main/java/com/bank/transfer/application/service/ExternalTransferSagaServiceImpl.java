package com.bank.transfer.application.service;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.ClearingDecisionEnum;
import com.bank.transfer.application.dto.ClearingInstructionDto;
import com.bank.transfer.application.dto.ClearingOutcomeDto;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.ClearingHouseUnavailableException;
import com.bank.transfer.application.port.in.CreateExternalTransferInPort;
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

  public ExternalTransferSagaServiceImpl(AccountRepositoryOutPort accountRepositoryOutPort,
    ExternalTransferRepositoryOutPort externalTransferRepositoryOutPort, FraudCheckOutPort fraudCheckOutPort,
    NotificationOutPort notificationOutPort, ClearingHouseOutPort clearingHouseOutPort,
    TransactionTemplate transactionTemplate) {
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
      return transactionTemplate.execute(status -> {
        Transfer rejected = transfer.reject();
        externalTransferRepositoryOutPort.save(rejected, String.join("; ", fraudCheckResultDto.reasons()));
        notificationOutPort.publish(toNotification(rejected, fraudCheckResultDto.reasons()));
        return new TransferResultDto(rejected.id(), rejected.status().name(), fraudCheckResultDto.reasons());
      });
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
      return new TransferResultDto(sent.id(), sent.status().name(), List.of());
    } catch (ClearingHouseUnavailableException e) {
      LOG.warn("Instruction not received for transfer {}: {}", sent.id(), e.getClass().getSimpleName());
      applyOutcome(new ClearingOutcomeDto(sent.id(), ClearingDecisionEnum.REJECTED, CLEARING_UNAVAILABLE_REASON));
      return new TransferResultDto(sent.id(), TransferStatusEnum.REJECTED.name(),
        List.of(CLEARING_UNAVAILABLE_REASON));
    } catch (RuntimeException e) {
      LOG.warn("Clearing house outcome unknown for transfer {}: {}", sent.id(), e.getClass().getSimpleName());
      String status = markUnknownIfSent(sent.id());
      return new TransferResultDto(sent.id(), status, List.of());
    }
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
      try {
        if (applyOutcome(outcome)) {
          applied++;
        }
      } catch (RuntimeException e) {
        LOG.warn("Outcome failed for transfer {}: {}", outcome.transferId(), e.getClass().getSimpleName());
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
      try {
        ClearingOutcomeDto outcome = clearingHouseOutPort.queryStatus(id).orElseGet(() -> {
          LOG.info("Transfer {} not found at the clearing house", id);
          return new ClearingOutcomeDto(id, ClearingDecisionEnum.REJECTED, INSTRUCTION_NOT_FOUND_REASON);
        });
        if (applyOutcome(outcome)) {
          applied++;
        }
      } catch (RuntimeException e) {
        LOG.warn("Reconciliation failed for transfer {}: {}", id, e.getClass().getSimpleName());
      }
    }
    return applied;
  }

  private boolean applyOutcome(ClearingOutcomeDto outcome) {
    return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
      Optional<Transfer> found = externalTransferRepositoryOutPort.findByIdForUpdate(outcome.transferId());
      if (found.isEmpty()) {
        LOG.warn("Outcome for unknown transfer {}", outcome.transferId());
        return false;
      }
      Transfer transfer = found.get();
      if (transfer.status() != TransferStatusEnum.SENT && transfer.status() != TransferStatusEnum.UNKNOWN) {
        LOG.info("Duplicate outcome ignored for transfer {}: {}", transfer.id(), transfer.status());
        return false;
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
      return true;
    }));
  }

  private static TransferNotificationDto toNotification(Transfer concluded, List<String> reasons) {
    return new TransferNotificationDto(concluded.id(), concluded.source().value(), concluded.dest().value(),
      concluded.money().amount(), concluded.money().currency(), concluded.status(), reasons);
  }
}
