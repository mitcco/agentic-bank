package com.bank.transfer.application.service;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.TransferRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CreateTransferServiceImpl implements CreateTransferInPort {

  private final AccountRepositoryOutPort accountRepositoryOutPort;
  private final TransferRepositoryOutPort transferRepositoryOutPort;
  private final FraudCheckOutPort fraudCheckOutPort;
  private final TransactionTemplate transactionTemplate;

  public CreateTransferServiceImpl(AccountRepositoryOutPort accountRepositoryOutPort,
    TransferRepositoryOutPort transferRepositoryOutPort, FraudCheckOutPort fraudCheckOutPort,
    TransactionTemplate transactionTemplate) {
    this.accountRepositoryOutPort = accountRepositoryOutPort;
    this.transferRepositoryOutPort = transferRepositoryOutPort;
    this.fraudCheckOutPort = fraudCheckOutPort;
    this.transactionTemplate = transactionTemplate;
  }

  @Override
  public TransferResultDto executeTransfer(CreateTransferCommand command) {
    Iban source = new Iban(command.sourceIban());
    Iban dest = new Iban(command.destIban());
    Money money = new Money(command.amount(), command.currency());
    Transfer transfer = Transfer.create(source, dest, money);

    requireExistingAccount(source, "Source Account not found");
    requireExistingAccount(dest, "Destination Account not found");

    FraudCheckResultDto fraudCheckResultDto =
      fraudCheckOutPort.checkFraud(source.value(), dest.value(), money.amount(), money.currency());

    return transactionTemplate.execute(status -> {
      if (FraudStatusEnum.ALLOWED.equals(fraudCheckResultDto.decision())) {
        Transfer completed = completeTransfer(transfer);

        return new TransferResultDto(completed.id(), completed.status().name(), fraudCheckResultDto.reason());
      }
      Transfer rejected = transfer.reject();
      transferRepositoryOutPort.save(rejected);

      return new TransferResultDto(rejected.id(), rejected.status().name(), fraudCheckResultDto.reason());
    });
  }

  //per evitare che l'antifrode dia rejected per l'amount eccedente anche su IBAN non esistenti
  private void requireExistingAccount(Iban iban, String message) {
    if (accountRepositoryOutPort.findByIban(iban).isEmpty()) {
      throw new AccountNotFoundException(message);
    }
  }

  private Transfer completeTransfer(Transfer transfer) {
    Iban source = transfer.source();
    Iban dest = transfer.dest();
    Map<Iban, Account> accountsMap = getAccountsMap(source, dest);
    Account sourceAccount = accountsMap.get(source);
    Account destAccount = accountsMap.get(dest);

    Money money = transfer.money();
    Account updatedSourceAccount = sourceAccount.charge(money);
    Account updatedDestAccount = destAccount.credit(money);

    accountRepositoryOutPort.save(updatedSourceAccount);
    accountRepositoryOutPort.save(updatedDestAccount);

    Transfer completed = transfer.complete();
    transferRepositoryOutPort.save(completed);

    return completed;
  }

  private Map<Iban, Account> getAccountsMap(Iban source, Iban dest) {
    List<Iban> ordered = Stream.of(source, dest).sorted(Comparator.comparing(Iban::value)).toList();

    Map<Iban, Account> accountsMap = new HashMap<>();
    for (Iban iban : ordered) {
      accountsMap.put(iban, accountRepositoryOutPort.findByIbanForUpdate(iban)
                              .orElseThrow(() -> new AccountNotFoundException("Account not found for IBAN: " + iban.value())));
    }
    return accountsMap;
  }
}
