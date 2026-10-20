package com.bank.transfer.rest.port.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.application.exception.TransferNotFoundException;
import com.bank.transfer.rest.dto.response.TransferDetailsResponseDto;
import com.bank.transfer.rest.port.in.rest.exception.WebExceptionHandler;
import com.bank.transfer.rest.servicedto.TransferQueryServiceDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransferQueryControllerTest {

  private static final String SOURCE_MASKED = "IT60" + "*".repeat(19) + "0001";
  private static final String DEST_MASKED = "IT60" + "*".repeat(19) + "0149";

  private ResultActions getTransfer(TransferQueryServiceDto service, String id) throws Exception {
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TransferQueryController(service))
        .setControllerAdvice(new WebExceptionHandler())
        .build();
    return mockMvc.perform(get("/transfers/{id}", id));
  }

  private static TransferDetailsResponseDto details(String status, List<String> reasons) {
    return new TransferDetailsResponseDto("t-1", SOURCE_MASKED, DEST_MASKED, new BigDecimal("100.25"), "EUR",
        status, reasons);
  }

  @Test
  void returns200WithTheDetailsOfASentTransfer() throws Exception {
    TransferQueryServiceDto service = id -> details("SENT", List.of());

    getTransfer(service, "t-1")
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.transferId").value("t-1"))
        .andExpect(jsonPath("$.sourceIbanMasked").value(SOURCE_MASKED))
        .andExpect(jsonPath("$.destIbanMasked").value(DEST_MASKED))
        .andExpect(jsonPath("$.amount").value(100.25))
        .andExpect(jsonPath("$.currency").value("EUR"))
        .andExpect(jsonPath("$.transferStatus").value("SENT"))
        .andExpect(jsonPath("$.reasons").isArray())
        .andExpect(jsonPath("$.reasons").isEmpty());
  }

  @Test
  void returns200AndNot202ForTheUnknownStatus() throws Exception {
    TransferQueryServiceDto service = id -> details("UNKNOWN", List.of());

    getTransfer(service, "t-1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transferStatus").value("UNKNOWN"));
  }

  @Test
  void returns200WithTheReasonsOfARejectedTransfer() throws Exception {
    TransferQueryServiceDto service = id -> details("REJECTED", List.of("A", "B"));

    getTransfer(service, "t-1")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transferStatus").value("REJECTED"))
        .andExpect(jsonPath("$.reasons[0]").value("A"))
        .andExpect(jsonPath("$.reasons[1]").value("B"))
        .andExpect(jsonPath("$.reasons.length()").value(2));
  }

  @Test
  void returns404WhenTheTransferDoesNotExist() throws Exception {
    TransferQueryServiceDto service = id -> {
      throw new TransferNotFoundException();
    };

    String body = getTransfer(service, "demo-ext-777")
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.detail").value("Transfer not found"))
        .andReturn().getResponse().getContentAsString();

    assertThat(body)
        .doesNotContain("demo-ext-777")
        .doesNotContain("IT60")
        .doesNotContain("Exception");
  }

  @Test
  void passesTheIdAsItIs() throws Exception {
    List<String> receivedIds = new ArrayList<>();
    TransferQueryServiceDto service = id -> {
      receivedIds.add(id);
      return details("COMPLETED", List.of());
    };

    getTransfer(service, "demo-ext-001").andExpect(status().isOk());

    assertThat(receivedIds).containsExactly("demo-ext-001");
  }
}
