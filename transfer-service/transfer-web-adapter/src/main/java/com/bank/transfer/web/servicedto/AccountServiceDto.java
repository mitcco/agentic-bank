package com.bank.transfer.web.servicedto;

import com.bank.transfer.web.dto.response.AccountResponseDto;

public interface AccountServiceDto {

  AccountResponseDto getAccount(String iban);
}
