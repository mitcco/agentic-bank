package com.bank.transfer.rest.servicedto;

import com.bank.transfer.rest.dto.response.AccountResponseDto;

public interface AccountServiceDto {

  AccountResponseDto getAccount(String iban);
}
