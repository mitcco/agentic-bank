package com.bank.transfer.adapter.in.web.mapper;

import com.bank.transfer.adapter.in.web.dto.AccountResponseDto;
import com.bank.transfer.application.dto.AccountDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface AccountDtoToAccountResponseDtoMapper {

  AccountResponseDto accountDtoToAccountResponseDto(AccountDto accountDto);
}
