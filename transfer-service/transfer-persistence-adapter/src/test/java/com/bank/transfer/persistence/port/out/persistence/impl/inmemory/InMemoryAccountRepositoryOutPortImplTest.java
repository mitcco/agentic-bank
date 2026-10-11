package com.bank.transfer.persistence.port.out.persistence.impl.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InMemoryAccountRepositoryOutPortImplTest {

  private static final Iban IBAN = new Iban("IT60X0542811101000000123456");
  private static final Iban OTHER = new Iban("IT60X0542811101000000654321");

  private final InMemoryAccountRepositoryOutPortImpl repository = new InMemoryAccountRepositoryOutPortImpl();

  private static Account account(String id, Iban iban, String amount) {
    return new Account(id, iban, new Money(new BigDecimal(amount), "EUR"));
  }

  @Test
  void isPreloadedWithDemoAccounts() {
    assertThat(repository.findByIban(new Iban("IT60X0542811101000000000001")))
        .contains(account("ac1", new Iban("IT60X0542811101000000000001"), "1000.00"));
    assertThat(repository.findByIban(new Iban("IT60X0542811101000000000002")))
        .contains(account("ac2", new Iban("IT60X0542811101000000000002"), "250.50"));
    assertThat(repository.findByIban(new Iban("IT60X0542811101000000000003")))
        .contains(account("ac3", new Iban("IT60X0542811101000000000003"), "0.00"));
  }

  @Test
  void findByIbanReturnsEmptyWhenAccountDoesNotExist() {
    assertThat(repository.findByIban(IBAN)).isEmpty();
  }

  @Test
  void findByIbanReturnsSavedAccount() {
    Account account = account("acc-1", IBAN, "100");
    repository.save(account);

    assertThat(repository.findByIban(IBAN)).contains(account);
  }

  @Test
  void findByIbanDoesNotReturnAccountsOfOtherIbans() {
    repository.save(account("acc-1", IBAN, "100"));

    assertThat(repository.findByIban(OTHER)).isEmpty();
  }

  @Test
  void findByIbanIgnoresIbanFormatting() {
    Account account = account("acc-1", IBAN, "100");
    repository.save(account);

    assertThat(repository.findByIban(new Iban("it60 x054 2811 1010 0000 0123 456")))
        .contains(account);
  }

  @Test
  void saveReplacesAccountWithSameIban() {
    repository.save(account("acc-1", IBAN, "100"));
    Account updated = account("acc-1", IBAN, "70");

    repository.save(updated);

    assertThat(repository.findByIban(IBAN)).contains(updated);
  }

  @Test
  void findByIbanForUpdateBehavesLikeFindByIban() {
    Account account = account("acc-1", IBAN, "100");
    repository.save(account);

    assertThat(repository.findByIbanForUpdate(IBAN)).contains(account);
    assertThat(repository.findByIbanForUpdate(OTHER)).isEmpty();
  }

  @Test
  void rejectsNulls() {
    assertThatThrownBy(() -> repository.findByIban(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repository.findByIbanForUpdate(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repository.save(null)).isInstanceOf(NullPointerException.class);
  }
}
