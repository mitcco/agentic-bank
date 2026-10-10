package com.bank.transfer.adapter.in.web.servicedto;

import com.bank.transfer.adapter.in.web.dto.AccountResponseDto;

public interface AccountServiceDto {

  AccountResponseDto getAccount(String iban);
}
