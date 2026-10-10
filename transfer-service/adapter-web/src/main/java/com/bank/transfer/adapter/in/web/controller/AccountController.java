package com.bank.transfer.adapter.in.web.controller;

import com.bank.transfer.adapter.in.web.dto.AccountResponseDto;
import com.bank.transfer.adapter.in.web.servicedto.AccountServiceDto;
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
