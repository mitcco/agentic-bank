package com.bank.transfer.postgres.port.out.postgres.impl;

import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.ExternalTransferRepositoryOutPort;
import com.bank.transfer.application.port.out.TransferRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.postgres.repository.SpringDataExternalTransferRepository;
import com.bank.transfer.postgres.repository.SpringDataTransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the demo data of {@link DemoDataCatalog}, inserting only the rows that are missing: accounts are looked up
 * by IBAN, transfers by id. The data is deterministic, but its balances are coherent with the transfers only on an
 * empty database: an existing row is never modified, so if a demo account already exists the missing transfers do
 * not correct its balance. Disabled with {@code transfer.demo-data.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "transfer.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(DemoDataSeeder.class);

  private final AccountRepositoryOutPort accountRepository;
  private final TransferRepositoryOutPort transferRepository;
  private final ExternalTransferRepositoryOutPort externalTransferRepository;
  private final SpringDataTransferRepository springDataTransfers;
  private final SpringDataExternalTransferRepository springDataExternalTransfers;

  DemoDataSeeder(AccountRepositoryOutPort accountRepository, TransferRepositoryOutPort transferRepository,
    ExternalTransferRepositoryOutPort externalTransferRepository, SpringDataTransferRepository springDataTransfers,
    SpringDataExternalTransferRepository springDataExternalTransfers) {
    this.accountRepository = accountRepository;
    this.transferRepository = transferRepository;
    this.externalTransferRepository = externalTransferRepository;
    this.springDataTransfers = springDataTransfers;
    this.springDataExternalTransfers = springDataExternalTransfers;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    int accounts = 0;
    for (Account account : DemoDataCatalog.accounts()) {
      if (accountRepository.findByIban(account.iban()).isEmpty()) {
        accountRepository.save(account);
        accounts++;
      }
    }
    int transfers = 0;
    for (Transfer transfer : DemoDataCatalog.internalTransfers()) {
      if (!springDataTransfers.existsById(transfer.id())) {
        transferRepository.save(transfer);
        transfers++;
      }
    }
    int externalTransfers = 0;
    for (DemoDataCatalog.ExternalSeed seed : DemoDataCatalog.externalTransfers()) {
      if (!springDataExternalTransfers.existsById(seed.transfer().id())) {
        externalTransferRepository.save(seed.transfer(), seed.reason());
        externalTransfers++;
      }
    }
    LOG.info("Demo data: {} accounts, {} transfers, {} external transfers inserted", accounts, transfers,
      externalTransfers);
  }
}
