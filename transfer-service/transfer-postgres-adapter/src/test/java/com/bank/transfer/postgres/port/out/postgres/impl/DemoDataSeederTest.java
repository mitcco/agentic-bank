package com.bank.transfer.postgres.port.out.postgres.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.postgres.port.out.postgres.impl.jpa.JpaAccountRepositoryOutPortImpl;
import com.bank.transfer.postgres.port.out.postgres.impl.jpa.JpaTransferRepositoryOutPortImpl;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({JpaAccountRepositoryOutPortImpl.class, JpaTransferRepositoryOutPortImpl.class, DemoDataSeeder.class})
class DemoDataSeederTest {

  private static final Iban ACC1 = new Iban("IT60X0542811101000000000001");
  private static final Iban ACC2 = new Iban("IT60X0542811101000000000002");
  private static final Iban ACC3 = new Iban("IT60X0542811101000000000003");

  @Autowired
  private DemoDataSeeder seeder;

  @Autowired
  private JpaAccountRepositoryOutPortImpl accounts;

  @Autowired
  private JpaTransferRepositoryOutPortImpl transfers;

  private static Money eur(String amount) {
    return new Money(new BigDecimal(amount), "EUR");
  }

  @Test
  void loadsDemoAccountsAndTransfers() {
    seeder.run(null);

    assertThat(accounts.findByIban(ACC1)).contains(new Account("ac1", ACC1, eur("1000.00")));
    assertThat(accounts.findByIban(ACC2)).contains(new Account("ac2", ACC2, eur("250.50")));
    assertThat(accounts.findByIban(ACC3)).contains(new Account("ac3", ACC3, eur("0.00")));
    assertThat(transfers.findById("tr1"))
      .contains(new Transfer("tr1", ACC1, ACC2, eur("100.00"), TransferStatusEnum.COMPLETED));
    assertThat(transfers.findById("tr2"))
      .contains(new Transfer("tr2", ACC2, ACC3, eur("50.25"), TransferStatusEnum.COMPLETED));
    assertThat(transfers.findById("tr3"))
      .contains(new Transfer("tr3", ACC3, ACC1, eur("5000.00"), TransferStatusEnum.REJECTED));
  }

  @Test
  void doesNotOverwriteExistingDataWhenRunAgain() {
    seeder.run(null);
    accounts.save(new Account("ac1", ACC1, eur("1.00")));

    seeder.run(null);

    assertThat(accounts.findByIban(ACC1)).contains(new Account("ac1", ACC1, eur("1.00")));
  }
}
