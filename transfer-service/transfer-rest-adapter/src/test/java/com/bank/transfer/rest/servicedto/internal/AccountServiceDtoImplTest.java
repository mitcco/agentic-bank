package com.bank.transfer.rest.servicedto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.rest.dto.response.AccountResponseDto;
import com.bank.transfer.rest.mapper.AccountDtoToAccountResponseDtoMapperImpl;
import com.bank.transfer.rest.servicedto.AccountServiceDto;
import com.bank.transfer.application.dto.AccountDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.port.in.AccountReadOnlyInPort;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountServiceDtoImplTest {

  private static final String IBAN = "IT60X0542811101000000000001";

  private final List<String> requestedIbans = new ArrayList<>();

  private AccountServiceDto serviceWith(AccountReadOnlyInPort port) {
    return new AccountServiceDtoImpl(port, new AccountDtoToAccountResponseDtoMapperImpl());
  }

  @Test
  void callsThePortAndMapsTheResultToResponseDto() {
    AccountReadOnlyInPort port = iban -> {
      requestedIbans.add(iban);
      return new AccountDto(IBAN, new BigDecimal("1000.00"), "EUR");
    };

    AccountResponseDto response = serviceWith(port).getAccount(IBAN);

    assertThat(requestedIbans).containsExactly(IBAN);
    assertThat(response.iban()).isEqualTo(IBAN);
    assertThat(response.amount()).isEqualByComparingTo("1000.00");
    assertThat(response.currency()).isEqualTo("EUR");
  }

  @Test
  void propagatesPortExceptions() {
    AccountReadOnlyInPort port = iban -> {
      throw new AccountNotFoundException("Account not found");
    };

    assertThatThrownBy(() -> serviceWith(port).getAccount(IBAN))
        .isInstanceOf(AccountNotFoundException.class);
  }
}
