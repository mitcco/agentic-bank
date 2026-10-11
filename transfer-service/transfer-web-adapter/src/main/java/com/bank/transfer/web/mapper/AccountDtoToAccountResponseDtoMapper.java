package com.bank.transfer.web.mapper;

import com.bank.transfer.web.dto.response.AccountResponseDto;
import com.bank.transfer.application.dto.AccountDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountDtoToAccountResponseDtoMapper {

  AccountResponseDto accountDtoToAccountResponseDto(AccountDto accountDto);
}
