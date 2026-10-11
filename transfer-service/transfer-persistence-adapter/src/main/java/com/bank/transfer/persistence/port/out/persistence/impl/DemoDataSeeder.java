package com.bank.transfer.persistence.port.out.persistence.impl;

import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.TransferRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Loads the same demo data as the in-memory implementations, only if the database is still empty.
 */
@Component
@Profile("!inmemory")
public class DemoDataSeeder implements ApplicationRunner {

  private static final Iban ACC1 = new Iban("IT60X0542811101000000000001");

  private final AccountRepositoryOutPort accountRepository;
  private final TransferRepositoryOutPort transferRepository;

  DemoDataSeeder(AccountRepositoryOutPort accountRepository, TransferRepositoryOutPort transferRepository) {
    this.accountRepository = accountRepository;
    this.transferRepository = transferRepository;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (accountRepository.findByIban(ACC1).isPresent()) {
      return;
    }
    accountRepository.save(demoAccount("ac1", "IT60X0542811101000000000001", "1000.00"));
    accountRepository.save(demoAccount("ac2", "IT60X0542811101000000000002", "250.50"));
    accountRepository.save(demoAccount("ac3", "IT60X0542811101000000000003", "0.00"));

    transferRepository.save(demoTransfer("tr1", "IT60X0542811101000000000001", "IT60X0542811101000000000002",
      "100.00", TransferStatusEnum.COMPLETED));
    transferRepository.save(demoTransfer("tr2", "IT60X0542811101000000000002", "IT60X0542811101000000000003",
      "50.25", TransferStatusEnum.COMPLETED));
    transferRepository.save(demoTransfer("tr3", "IT60X0542811101000000000003", "IT60X0542811101000000000001",
      "5000.00", TransferStatusEnum.REJECTED));
  }

  private static Account demoAccount(String id, String iban, String amount) {
    return new Account(id, new Iban(iban), new Money(new BigDecimal(amount), "EUR"));
  }

  private static Transfer demoTransfer(String id, String source, String dest, String amount,
    TransferStatusEnum status) {
    return new Transfer(id, new Iban(source), new Iban(dest), new Money(new BigDecimal(amount), "EUR"), status);
  }
}
