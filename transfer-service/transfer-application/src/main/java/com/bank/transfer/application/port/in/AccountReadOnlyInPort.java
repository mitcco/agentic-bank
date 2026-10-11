package com.bank.transfer.application.port.in;

import com.bank.transfer.application.dto.AccountDto;

public interface AccountReadOnlyInPort {

  AccountDto getAccount(String iban);
}
