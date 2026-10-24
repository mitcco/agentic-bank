package com.bank.transfer.rest.port.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.rest.port.in.rest.exception.WebExceptionHandler;
import com.bank.transfer.rest.servicedto.AccountServiceDto;
import com.bank.transfer.rest.servicedto.TransferServiceDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountControllerNotFoundBodyTest {

  private static final String IBAN = "IT60X0542811101000000000001";
  private static final String IBAN_LAST_8_DIGITS = "00000001";
  private static final String IBAN_SIGNIFICANT_PART = "0542811101";

  private static final String TRANSFER_BODY = """
      {"sourceIban":"IT60X0542811101000000000001","destIban":"IT60X0542811101000000000002",
       "amount":10.00,"currency":"EUR"}""";

  private MockMvc mockMvcThrowingNotFound() {
    AccountServiceDto service = iban -> {
      throw new AccountNotFoundException("Account not found");
    };
    return MockMvcBuilders.standaloneSetup(new AccountController(service))
        .setControllerAdvice(new WebExceptionHandler())
        .build();
  }

  @Test
  void notFoundHasFixedDetailAndFixedInstanceAndTheWholeBodyDoesNotContainTheIban() throws Exception {
    MvcResult result = mockMvcThrowingNotFound().perform(get("/accounts/{iban}", IBAN))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Account not found"))
        .andExpect(jsonPath("$.instance").value("/accounts"))
        .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain(IBAN)
        .doesNotContain(IBAN_SIGNIFICANT_PART)
        .doesNotContain(IBAN_LAST_8_DIGITS);
  }

  @Test
  void notFoundForAnIbanWithSpacesAndLowerCaseHasFixedInstanceAndTheWholeBodyDoesNotContainIt()
      throws Exception {
    MvcResult result = mockMvcThrowingNotFound()
        .perform(get("/accounts/{iban}", "it60 x054 2811 1010 0000 0000 001"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Account not found"))
        .andExpect(jsonPath("$.instance").value("/accounts"))
        .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContainIgnoringCase("it60")
        .doesNotContainIgnoringCase("x054")
        .doesNotContain("0542811101")
        .doesNotContain("2811")
        .doesNotContain("0000 0001")
        .doesNotContain("%20")
        .doesNotContain(IBAN_LAST_8_DIGITS);
  }

  @Test
  void notFoundOfAPostedTransferHasFixedInstanceAndTheWholeBodyDoesNotContainAnyIban() throws Exception {
    TransferServiceDto service = request -> {
      throw new AccountNotFoundException("Account not found");
    };
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TransferController(service))
        .setControllerAdvice(new WebExceptionHandler())
        .build();

    MvcResult result = mockMvc
        .perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(TRANSFER_BODY))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Account not found"))
        .andExpect(jsonPath("$.instance").value("/accounts"))
        .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("IT60X0542811101000000000001")
        .doesNotContain("IT60X0542811101000000000002")
        .doesNotContain(IBAN_SIGNIFICANT_PART)
        .doesNotContain(IBAN_LAST_8_DIGITS)
        .doesNotContain("00000002");
  }
}
