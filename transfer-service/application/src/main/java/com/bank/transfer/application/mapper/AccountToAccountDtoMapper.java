package com.bank.transfer.application.mapper;

import com.bank.transfer.application.dto.AccountDto;
import com.bank.transfer.domain.Account;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AccountToAccountDtoMapper {

  @Mapping(source = "money.amount", target = "amount")
  @Mapping(source = "money.currency", target = "currency")
  @Mapping(source = "iban.value", target = "iban")
  AccountDto accountToAccountDto(Account account);
}
