package com.bank.transfer.adapter.out.persistence.jpa;

import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!inmemory")
public class JpaAccountRepositoryOutPortImpl implements AccountRepositoryOutPort {

  private final SpringDataAccountRepository repository;

  JpaAccountRepositoryOutPortImpl(SpringDataAccountRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<Account> findByIban(Iban iban) {
    Objects.requireNonNull(iban);
    return repository.findByIban(iban.value()).map(JpaAccountRepositoryOutPortImpl::toDomain);
  }

  @Override
  public Optional<Account> findByIbanForUpdate(Iban iban) {
    Objects.requireNonNull(iban);
    return repository.findByIbanForUpdate(iban.value()).map(JpaAccountRepositoryOutPortImpl::toDomain);
  }

  @Override
  public Account save(Account account) {
    Objects.requireNonNull(account);
    return toDomain(repository.save(toEntity(account)));
  }

  private static AccountEntity toEntity(Account account) {
    return new AccountEntity(account.id(), account.iban().value(), account.money().amount(),
      account.money().currency());
  }

  private static Account toDomain(AccountEntity entity) {
    return new Account(entity.getId(), new Iban(entity.getIban()),
      new Money(entity.getBalance(), entity.getCurrency()));
  }
}
