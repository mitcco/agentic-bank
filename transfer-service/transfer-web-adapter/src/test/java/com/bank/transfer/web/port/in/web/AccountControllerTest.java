package com.bank.transfer.web.port.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.web.port.in.web.exception.WebExceptionHandler;
import com.bank.transfer.web.dto.response.AccountResponseDto;
import com.bank.transfer.web.servicedto.AccountServiceDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountControllerTest {

  private static final String IBAN = "IT60X0542811101000000000001";

  private final List<String> requestedIbans = new ArrayList<>();

  private MockMvc mockMvcWith(AccountServiceDto service) {
    return MockMvcBuilders.standaloneSetup(new AccountController(service))
        .setControllerAdvice(new WebExceptionHandler())
        .build();
  }

  @Test
  void returnsAccountForIban() throws Exception {
    AccountServiceDto service = iban -> {
      requestedIbans.add(iban);
      return new AccountResponseDto(IBAN, new BigDecimal("1000.00"), "EUR");
    };

    mockMvcWith(service).perform(get("/accounts/{iban}", IBAN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.iban").value(IBAN))
        .andExpect(jsonPath("$.amount").value(1000.00))
        .andExpect(jsonPath("$.currency").value("EUR"));

    assertThat(requestedIbans).containsExactly(IBAN);
  }

  @Test
  void returns404WhenAccountDoesNotExist() throws Exception {
    AccountServiceDto service = iban -> {
      throw new AccountNotFoundException("No account found for iban " + iban);
    };

    mockMvcWith(service).perform(get("/accounts/{iban}", IBAN))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("No account found for iban " + IBAN));
  }

  @Test
  void returns400WhenIbanIsMalformed() throws Exception {
    AccountServiceDto service = iban -> {
      throw new IllegalArgumentException("Malformed IBAN, it has to be 27 characters");
    };

    mockMvcWith(service).perform(get("/accounts/{iban}", "IT60"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Malformed IBAN, it has to be 27 characters"));
  }
}
