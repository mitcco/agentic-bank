package com.bank.transfer.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.adapter.in.web.controller.TransferController;
import com.bank.transfer.adapter.in.web.dto.TransferResponseDto;
import com.bank.transfer.adapter.in.web.servicedto.TransferServiceDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.domain.exception.DifferentCurrencyException;
import com.bank.transfer.domain.exception.InsufficientFundException;
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
    TransferServiceDto service = request -> new TransferResponseDto("t-1", "COMPLETED", "");

    postTransfer(service)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transferId").value("t-1"))
        .andExpect(jsonPath("$.transferStatus").value("COMPLETED"));
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
