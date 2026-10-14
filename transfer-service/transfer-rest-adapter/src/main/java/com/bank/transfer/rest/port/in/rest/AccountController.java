package com.bank.transfer.rest.port.in.rest;

import com.bank.transfer.rest.dto.response.AccountResponseDto;
import com.bank.transfer.rest.servicedto.AccountServiceDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

  private final AccountServiceDto accountServiceDto;

  public AccountController(AccountServiceDto accountServiceDto) {
    this.accountServiceDto = accountServiceDto;
  }

  @GetMapping("/accounts/{iban}")
  public AccountResponseDto getAccount(@PathVariable String iban) {
    return accountServiceDto.getAccount(iban);
  }
}
