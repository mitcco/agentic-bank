package com.bank.transfer.adapter.in.web.servicedto.internal;

import com.bank.transfer.adapter.in.web.dto.AccountResponseDto;
import com.bank.transfer.adapter.in.web.mapper.AccountDtoToAccountResponseDtoMapper;
import com.bank.transfer.adapter.in.web.servicedto.AccountServiceDto;
import com.bank.transfer.application.port.in.AccountReadOnlyInPort;
import org.springframework.stereotype.Service;

@Service
public class AccountServiceDtoImpl implements AccountServiceDto {

  private final AccountReadOnlyInPort accountReadOnlyInPort;
  private final AccountDtoToAccountResponseDtoMapper mapper;

  public AccountServiceDtoImpl(AccountReadOnlyInPort accountReadOnlyInPort,
      AccountDtoToAccountResponseDtoMapper mapper) {
    this.accountReadOnlyInPort = accountReadOnlyInPort;
    this.mapper = mapper;
  }

  @Override
  public AccountResponseDto getAccount(String iban) {
    return mapper.accountDtoToAccountResponseDto(accountReadOnlyInPort.getAccount(iban));
  }
}
