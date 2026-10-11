package com.bank.transfer.persistence.port.out.persistence.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({JpaTransferRepositoryOutPortImpl.class, JpaAccountRepositoryOutPortImpl.class})
class JpaTransferRepositoryOutPortImplTest {

  private static final Iban SOURCE = new Iban("IT60X0542811101000000123456");
  private static final Iban DEST = new Iban("IT60X0542811101000000654321");

  @Autowired
  private JpaTransferRepositoryOutPortImpl repository;

  @Autowired
  private JpaAccountRepositoryOutPortImpl accountRepository;

  @Autowired
  private EntityManager entityManager;

  /**
   * transfer.source_iban and transfer.dest_iban reference account.iban, so both accounts must exist first.
   */
  @BeforeEach
  void createAccounts() {
    accountRepository.save(new Account("acc-source", SOURCE, new Money(new BigDecimal("100"), "EUR")));
    accountRepository.save(new Account("acc-dest", DEST, new Money(new BigDecimal("0"), "EUR")));
    flushAndClear();
  }

  private static Transfer transfer() {
    return Transfer.create(SOURCE, DEST, new Money(new BigDecimal("10.50"), "EUR"));
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  void findByIdReturnsEmptyWhenTransferDoesNotExist() {
    assertThat(repository.findById("missing")).isEmpty();
  }

  @Test
  void saveReturnsTheSavedTransfer() {
    Transfer transfer = transfer();

    assertThat(repository.save(transfer)).isEqualTo(transfer);
  }

  @Test
  void findByIdReturnsSavedTransferWithAllItsFields() {
    Transfer transfer = transfer();
    repository.save(transfer);
    flushAndClear();

    assertThat(repository.findById(transfer.id())).contains(transfer);
  }

  @Test
  void saveReplacesTransferWithSameId() {
    Transfer pending = transfer();
    repository.save(pending);
    flushAndClear();
    Transfer rejected = pending.reject();

    repository.save(rejected);
    flushAndClear();

    assertThat(repository.findById(pending.id())).contains(rejected);
  }

  @Test
  void rejectsNulls() {
    assertThatThrownBy(() -> repository.save(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repository.findById(null)).isInstanceOf(NullPointerException.class);
  }
}
