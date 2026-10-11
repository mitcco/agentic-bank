package com.bank.transfer.web.port.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.web.port.in.web.exception.WebExceptionHandler;
import com.bank.transfer.web.dto.response.TransferResponseDto;
import com.bank.transfer.web.servicedto.TransferServiceDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.domain.exception.DifferentCurrencyException;
import com.bank.transfer.domain.exception.InsufficientFundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransferControllerTest {

  private static final String BODY = """
      {"sourceIban":"IT60X0542811101000000000001","destIban":"IT60X0542811101000000000002",
       "amount":10.00,"currency":"EUR"}""";

  private ResultActions postTransfer(TransferServiceDto service) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TransferController(service))
        .setControllerAdvice(new WebExceptionHandler())
        .build();
    return mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY));
  }

  @Test
  void returnsTransferResult() throws Exception {
    TransferServiceDto service = request -> new TransferResponseDto("t-1", "COMPLETED", List.of());

    postTransfer(service)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transferId").value("t-1"))
        .andExpect(jsonPath("$.transferStatus").value("COMPLETED"))
        .andExpect(jsonPath("$.reasons").isArray())
        .andExpect(jsonPath("$.reasons").isEmpty());
  }

  @Test
  void returnsAllTheRejectionReasonsInOrderAndNoLegacyReasonField() throws Exception {
    TransferServiceDto service = request -> new TransferResponseDto("t-1", "REJECTED",
        List.of("Source IBAN is locked", "Destination IBAN is locked"));

    postTransfer(service)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transferStatus").value("REJECTED"))
        .andExpect(jsonPath("$.reasons[0]").value("Source IBAN is locked"))
        .andExpect(jsonPath("$.reasons[1]").value("Destination IBAN is locked"))
        .andExpect(jsonPath("$.reasons.length()").value(2))
        .andExpect(jsonPath("$.reason").doesNotExist());
  }

  @Test
  void returns503WithAFixedBodyWhenTheFraudCheckIsUnavailable() throws Exception {
    TransferServiceDto service = request -> {
      throw new FraudCheckUnavailableException("t-1");
    };

    String body = postTransfer(service)
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(503))
        .andExpect(jsonPath("$.title").value("Service Unavailable"))
        .andExpect(jsonPath("$.detail")
            .value("Fraud check is temporarily unavailable: the transfer was not executed"))
        .andReturn().getResponse().getContentAsString();

    assertThat(body)
        .doesNotContain("t-1")
        .doesNotContain("IT60X0542811101000000000001")
        .doesNotContain("IT60X0542811101000000000002")
        .doesNotContain("10.00")
        .doesNotContain("Exception");
  }

  @Test
  void returns404WhenAnAccountDoesNotExist() throws Exception {
    TransferServiceDto service = request -> {
      throw new AccountNotFoundException("Source Account not found");
    };

    postTransfer(service)
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Source Account not found"));
  }

  @Test
  void returns400WhenTheRequestIsInvalid() throws Exception {
    TransferServiceDto service = request -> {
      throw new IllegalArgumentException("Transfer amount has to be greater than 0");
    };

    postTransfer(service)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Transfer amount has to be greater than 0"));
  }

  @Test
  void returns422WhenFundsAreInsufficient() throws Exception {
    TransferServiceDto service = request -> {
      throw new InsufficientFundException();
    };

    postTransfer(service)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.detail").value("Insufficient Fund on your account"));
  }

  @Test
  void returns422WhenCurrenciesDiffer() throws Exception {
    TransferServiceDto service = request -> {
      throw new DifferentCurrencyException("Different currency");
    };

    postTransfer(service)
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.detail").value("Different currency"));
  }
}
