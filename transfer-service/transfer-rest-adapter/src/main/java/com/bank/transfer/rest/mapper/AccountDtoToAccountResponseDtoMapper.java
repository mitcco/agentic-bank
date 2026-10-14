package com.bank.transfer.rest.mapper;

import com.bank.transfer.rest.dto.response.AccountResponseDto;
import com.bank.transfer.application.dto.AccountDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountDtoToAccountResponseDtoMapper {

  AccountResponseDto accountDtoToAccountResponseDto(AccountDto accountDto);
}
