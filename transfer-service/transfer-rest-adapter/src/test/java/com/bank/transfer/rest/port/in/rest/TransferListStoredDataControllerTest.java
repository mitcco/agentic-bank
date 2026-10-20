package com.bank.transfer.rest.port.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.application.exception.StoredTransferDataException;
import com.bank.transfer.rest.port.in.rest.exception.WebExceptionHandler;
import com.bank.transfer.rest.servicedto.TransferListServiceDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransferListStoredDataControllerTest {

  private static final String IBAN = "IT60X0542811101000000000102";

  private static ResultActions list(RuntimeException exception) throws Exception {
    TransferListServiceDto service = (iban, direction, statuses, type, from, to, page, size) -> {
      throw exception;
    };
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TransferListController(service))
      .setControllerAdvice(new WebExceptionHandler())
      .build();
    return mockMvc.perform(get("/transfers").param("iban", IBAN));
  }

  @Test
  void returns500WithAFixedDetailWhenAStoredRowIsNotValid() throws Exception {
    list(new StoredTransferDataException())
      .andExpect(status().isInternalServerError())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
      .andExpect(jsonPath("$.status").value(500))
      .andExpect(jsonPath("$.detail").value("Stored transfer data is not valid"))
      .andExpect(jsonPath("$.instance").value("/transfers"));
  }

  @Test
  void the500BodyNeverContainsTheIbanAmountsNorExceptionNames() throws Exception {
    String body = list(new StoredTransferDataException())
      .andExpect(status().isInternalServerError())
      .andReturn().getResponse().getContentAsString();

    assertThat(body)
      .doesNotContain(IBAN)
      .doesNotContain("IT60")
      .doesNotContain("10.")
      .doesNotContain("Exception");
  }

  @Test
  void the400ForInvalidParametersIsUnchanged() throws Exception {
    list(new IllegalArgumentException("size must be between 1 and 100"))
      .andExpect(status().isBadRequest())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
      .andExpect(jsonPath("$.detail").value("size must be between 1 and 100"))
      .andExpect(jsonPath("$.instance").value("/transfers"));
  }
}
