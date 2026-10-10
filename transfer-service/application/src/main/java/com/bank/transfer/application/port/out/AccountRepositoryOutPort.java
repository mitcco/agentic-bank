package com.bank.transfer.application.port.out;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import java.util.Optional;

public interface AccountRepositoryOutPort {

  Optional<Account> findByIban(Iban iban);

  /**
   * Same as {@link #findByIban(Iban)} but the account is locked until the end of the surrounding transaction.
   */
  Optional<Account> findByIbanForUpdate(Iban iban);

  Account save(Account account);

}
