package com.bank.transfer.persistence.port.out.persistence.impl.inmemory;

import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("inmemory")
public class InMemoryAccountRepositoryOutPortImpl implements AccountRepositoryOutPort {

  private final Map<String, Account> accounts = new ConcurrentHashMap<>();

  public InMemoryAccountRepositoryOutPortImpl() {
    loadDemoAccounts();
  }

  @Override
  public Optional<Account> findByIban(Iban iban) {
    Objects.requireNonNull(iban);
    return Optional.ofNullable(accounts.get(iban.value()));
  }

  /*
  Nothing to lock on a Map: the in-memory implementation gives no isolation between transactions
   */
  @Override
  public Optional<Account> findByIbanForUpdate(Iban iban) {
    return findByIban(iban);
  }

  @Override
  public Account save(Account account) {
    Objects.requireNonNull(account);
    return accounts.put(account.iban().value(), account);
  }

  private void loadDemoAccounts() {
    save(demoAccount("ac1", "IT60X0542811101000000000001", "1000.00"));
    save(demoAccount("ac2", "IT60X0542811101000000000002", "250.50"));
    save(demoAccount("ac3", "IT60X0542811101000000000003", "0.00"));
  }

  private static Account demoAccount(String id, String iban, String amount) {
    return new Account(id, new Iban(iban), new Money(new BigDecimal(amount), "EUR"));
  }
}
