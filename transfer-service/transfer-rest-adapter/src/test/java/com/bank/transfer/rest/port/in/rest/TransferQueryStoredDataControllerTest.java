package com.bank.transfer.rest.port.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.application.exception.StoredTransferDataException;
import com.bank.transfer.application.exception.TransferNotFoundException;
import com.bank.transfer.rest.port.in.rest.exception.WebExceptionHandler;
import com.bank.transfer.rest.servicedto.TransferQueryServiceDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransferQueryStoredDataControllerTest {

  private static final String REQUESTED_ID = "stored-bad-777";

  private ResultActions getTransfer(TransferQueryServiceDto service, String id) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TransferQueryController(service))
        .setControllerAdvice(new WebExceptionHandler())
        .build();
    return mockMvc.perform(get("/transfers/{id}", id));
  }

  private static TransferQueryServiceDto failingWith(RuntimeException exception) {
    return id -> {
      throw exception;
    };
  }

  @Test
  void returns500WithAFixedDetailWhenTheStoredDataIsNotValid() throws Exception {
    getTransfer(failingWith(new StoredTransferDataException()), REQUESTED_ID)
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.detail").value("Stored transfer data is not valid"))
        .andExpect(jsonPath("$.instance").value("/transfers"));
  }

  @Test
  void the500BodyNeverContainsTheRequestedIdIbansAmountsNorExceptionNames() throws Exception {
    String body = getTransfer(failingWith(new StoredTransferDataException()), REQUESTED_ID)
        .andExpect(status().isInternalServerError())
        .andReturn().getResponse().getContentAsString();

    assertThat(body)
        .doesNotContain(REQUESTED_ID)
        .doesNotContain("stored-bad")
        .doesNotContain("IT60")
        .doesNotContain("10.")
        .doesNotContain("Exception");
  }

  @Test
  void the404ForAMissingTransferIsUnchanged() throws Exception {
    String body = getTransfer(failingWith(new TransferNotFoundException()), REQUESTED_ID)
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Transfer not found"))
        .andExpect(jsonPath("$.instance").value("/transfers"))
        .andReturn().getResponse().getContentAsString();

    assertThat(body).doesNotContain(REQUESTED_ID);
  }

  @Test
  void the400ForABlankIdIsUnchanged() throws Exception {
    getTransfer(failingWith(new IllegalArgumentException("transferId must not be blank")), REQUESTED_ID)
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("transferId must not be blank"));
  }
}
