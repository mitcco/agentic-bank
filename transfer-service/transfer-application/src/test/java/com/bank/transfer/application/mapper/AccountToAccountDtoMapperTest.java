package com.bank.transfer.application.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.dto.AccountDto;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(AccountToAccountDtoMapperImpl.class)
class AccountToAccountDtoMapperTest {

  @Autowired
  private AccountToAccountDtoMapper mapper;

  @Test
  void mapsIbanAmountAndCurrencyToFlatDto() {
    Account account = new Account("acc-1", new Iban("it60 x054 2811 1010 0000 0123 456"),
        new Money(new BigDecimal("125.50"), "EUR"));

    AccountDto dto = mapper.accountToAccountDto(account);

    assertThat(dto.iban()).isEqualTo("IT60X0542811101000000123456");
    assertThat(dto.amount()).isEqualByComparingTo("125.50");
    assertThat(dto.currency()).isEqualTo("EUR");
  }
}
