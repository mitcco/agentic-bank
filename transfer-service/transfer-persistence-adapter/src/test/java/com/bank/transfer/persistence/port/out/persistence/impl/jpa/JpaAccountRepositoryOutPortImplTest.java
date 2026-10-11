package com.bank.transfer.persistence.port.out.persistence.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(JpaAccountRepositoryOutPortImpl.class)
class JpaAccountRepositoryOutPortImplTest {

  private static final Iban IBAN = new Iban("IT60X0542811101000000123456");
  private static final Iban OTHER = new Iban("IT60X0542811101000000654321");

  @Autowired
  private JpaAccountRepositoryOutPortImpl repository;

  @Autowired
  private EntityManager entityManager;

  private static Account account(String id, Iban iban, String amount) {
    return new Account(id, iban, new Money(new BigDecimal(amount), "EUR"));
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  @Test
  void findByIbanReturnsEmptyWhenAccountDoesNotExist() {
    assertThat(repository.findByIban(IBAN)).isEmpty();
  }

  @Test
  void findByIbanReturnsSavedAccount() {
    Account account = account("acc-1", IBAN, "100.25");
    repository.save(account);
    flushAndClear();

    assertThat(repository.findByIban(IBAN)).contains(account);
  }

  @Test
  void findByIbanDoesNotReturnAccountsOfOtherIbans() {
    repository.save(account("acc-1", IBAN, "100"));
    flushAndClear();

    assertThat(repository.findByIban(OTHER)).isEmpty();
  }

  @Test
  void findByIbanIgnoresIbanFormatting() {
    Account account = account("acc-1", IBAN, "100");
    repository.save(account);
    flushAndClear();

    assertThat(repository.findByIban(new Iban("it60 x054 2811 1010 0000 0123 456"))).contains(account);
  }

  @Test
  void saveReturnsTheSavedAccount() {
    Account account = account("acc-1", IBAN, "100");

    assertThat(repository.save(account)).isEqualTo(account);
  }

  @Test
  void saveReplacesAccountWithSameId() {
    repository.save(account("acc-1", IBAN, "100"));
    flushAndClear();
    Account updated = account("acc-1", IBAN, "70");

    repository.save(updated);
    flushAndClear();

    assertThat(repository.findByIban(IBAN)).contains(updated);
  }

  @Test
  void findByIbanForUpdateReturnsSavedAccountAndLocksIt() {
    Account account = account("acc-1", IBAN, "100");
    repository.save(account);
    flushAndClear();

    assertThat(repository.findByIbanForUpdate(IBAN)).contains(account);

    Object managedEntity = entityManager.createQuery("select a from AccountEntity a").getSingleResult();
    assertThat(entityManager.getLockMode(managedEntity)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
  }

  @Test
  void findByIbanForUpdateReturnsEmptyWhenAccountDoesNotExist() {
    assertThat(repository.findByIbanForUpdate(IBAN)).isEmpty();
  }

  @Test
  void rejectsNulls() {
    assertThatThrownBy(() -> repository.findByIban(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repository.findByIbanForUpdate(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repository.save(null)).isInstanceOf(NullPointerException.class);
  }
}
