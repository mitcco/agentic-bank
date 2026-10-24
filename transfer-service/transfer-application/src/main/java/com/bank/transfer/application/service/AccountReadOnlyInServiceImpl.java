package com.bank.transfer.application.service;

import com.bank.transfer.application.dto.AccountDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.mapper.AccountToAccountDtoMapper;
import com.bank.transfer.application.port.in.AccountReadOnlyInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AccountReadOnlyInServiceImpl implements AccountReadOnlyInPort {

  private final AccountRepositoryOutPort accountRepositoryOutPort;
  private final AccountToAccountDtoMapper mapper;

  public AccountReadOnlyInServiceImpl(AccountRepositoryOutPort accountRepositoryOutPort, AccountToAccountDtoMapper mapper) {
    this.accountRepositoryOutPort = accountRepositoryOutPort;
    this.mapper = mapper;
  }

  @Override
  public AccountDto getAccount(String iban) {
    Iban ibanToRetrieve = new Iban(iban);
    Optional<Account> accountOpt = accountRepositoryOutPort.findByIban(ibanToRetrieve);
    if (accountOpt.isPresent()) {
      return mapper.accountToAccountDto(accountOpt.get());
    }
    throw new AccountNotFoundException("Account not found");
  }
}
