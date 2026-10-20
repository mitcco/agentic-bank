package com.bank.transfer.rest.port.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.rest.dto.response.TransferDetailsResponseDto;
import com.bank.transfer.rest.dto.response.TransferListItemResponseDto;
import com.bank.transfer.rest.dto.response.TransferListResponseDto;
import com.bank.transfer.rest.dto.response.TransferResponseDto;
import com.bank.transfer.rest.port.in.rest.exception.WebExceptionHandler;
import com.bank.transfer.rest.servicedto.TransferListServiceDto;
import com.bank.transfer.rest.servicedto.TransferQueryServiceDto;
import com.bank.transfer.rest.servicedto.TransferServiceDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransferListControllerTest {

  private static final String IBAN = "IT60X0542811101000000000141";
  private static final String COUNTERPARTY_MASKED = "IT60" + "*".repeat(19) + "0001";

  /** Hand written fake: records the raw parameters it receives and answers with the given response. */
  private static final class RecordingService implements TransferListServiceDto {

    private final List<List<Object>> calls = new ArrayList<>();
    private final TransferListResponseDto response;

    RecordingService(TransferListResponseDto response) {
      this.response = response;
    }

    @Override
    public TransferListResponseDto listTransfers(String iban, String direction, List<String> statuses, String type,
      String from, String to, String page, String size) {
      calls.add(Arrays.asList(iban, direction, statuses, type, from, to, page, size));
      return response;
    }
  }

  private static TransferListResponseDto emptyPage() {
    return new TransferListResponseDto(List.of(), 0, 20, 0, false);
  }

  private static MockMvc mockMvcWith(TransferListServiceDto service) {
    return MockMvcBuilders.standaloneSetup(new TransferListController(service))
      .setControllerAdvice(new WebExceptionHandler())
      .build();
  }

  private static ResultActions list(TransferListServiceDto service, MockHttpServletRequestBuilder request)
    throws Exception {
    return mockMvcWith(service).perform(request);
  }

  @Test
  void passesAllParametersAsTheyAre() throws Exception {
    RecordingService service = new RecordingService(emptyPage());

    list(service, get("/transfers")
      .param("iban", IBAN)
      .param("direction", "OUTGOING")
      .param("status", "COMPLETED", "REJECTED")
      .param("type", "EXTERNAL")
      .param("from", "2026-10-01T00:00:00Z")
      .param("to", "2026-10-19T00:00:00Z")
      .param("page", "2")
      .param("size", "10"))
      .andExpect(status().isOk());

    assertThat(service.calls).hasSize(1);
    assertThat(service.calls.get(0)).containsExactly(IBAN, "OUTGOING", List.of("COMPLETED", "REJECTED"), "EXTERNAL",
      "2026-10-01T00:00:00Z", "2026-10-19T00:00:00Z", "2", "10");
  }

  @Test
  void aRequestWithoutParametersReachesTheServiceWithNullsAndNoStatuses() throws Exception {
    RecordingService service = new RecordingService(emptyPage());

    list(service, get("/transfers")).andExpect(status().isOk());

    List<Object> call = service.calls.get(0);
    assertThat(call.get(0)).isNull();
    assertThat(call.get(1)).isNull();
    @SuppressWarnings("unchecked")
    List<String> statuses = (List<String>) call.get(2);
    assertThat(statuses == null || statuses.isEmpty()).isTrue();
    assertThat(call.subList(3, 8)).containsOnlyNulls();
  }

  @Test
  void theControllerDoesNotValidateAnythingItself() throws Exception {
    RecordingService service = new RecordingService(emptyPage());

    list(service, get("/transfers")
      .param("iban", " it60x0542811101000000000141")
      .param("direction", "sideways")
      .param("status", "BOGUS")
      .param("page", "abc")
      .param("size", "-5"))
      .andExpect(status().isOk());

    assertThat(service.calls.get(0)).containsExactly(" it60x0542811101000000000141", "sideways",
      List.of("BOGUS"), null, null, null, "abc", "-5");
  }

  @Test
  void returns200WithItemsAndPageMetadata() throws Exception {
    TransferListResponseDto response = new TransferListResponseDto(List.of(
      new TransferListItemResponseDto("t-1", "EXTERNAL", "OUTGOING", COUNTERPARTY_MASKED, new BigDecimal("59.73"),
        "EUR", "COMPLETED", Instant.parse("2026-10-19T08:30:00Z")),
      new TransferListItemResponseDto("t-2", "INTERNAL", "INCOMING", COUNTERPARTY_MASKED, new BigDecimal("32.46"),
        "EUR", "REJECTED", Instant.parse("2026-10-18T07:00:00Z"))), 2, 2, 7, true);

    list(new RecordingService(response), get("/transfers").param("iban", IBAN))
      .andExpect(status().isOk())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
      .andExpect(jsonPath("$.items.length()").value(2))
      .andExpect(jsonPath("$.items[0].transferId").value("t-1"))
      .andExpect(jsonPath("$.items[0].type").value("EXTERNAL"))
      .andExpect(jsonPath("$.items[0].direction").value("OUTGOING"))
      .andExpect(jsonPath("$.items[0].counterpartyIbanMasked").value(COUNTERPARTY_MASKED))
      .andExpect(jsonPath("$.items[0].amount").value(59.73))
      .andExpect(jsonPath("$.items[0].currency").value("EUR"))
      .andExpect(jsonPath("$.items[0].transferStatus").value("COMPLETED"))
      .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-19T08:30:00Z"))
      .andExpect(jsonPath("$.items[1].transferId").value("t-2"))
      .andExpect(jsonPath("$.items[1].direction").value("INCOMING"))
      .andExpect(jsonPath("$.items[1].transferStatus").value("REJECTED"))
      .andExpect(jsonPath("$.page").value(2))
      .andExpect(jsonPath("$.size").value(2))
      .andExpect(jsonPath("$.totalElements").value(7))
      .andExpect(jsonPath("$.hasNext").value(true));
  }

  @Test
  void anItemNeverShowsTheRequestedIbanNorTheReasons() throws Exception {
    TransferListResponseDto response = new TransferListResponseDto(List.of(
      new TransferListItemResponseDto("t-1", "EXTERNAL", "OUTGOING", COUNTERPARTY_MASKED, new BigDecimal("59.73"),
        "EUR", "COMPLETED", Instant.parse("2026-10-19T08:30:00Z"))), 0, 20, 1, false);

    String body = list(new RecordingService(response), get("/transfers").param("iban", IBAN))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items[0].reasons").doesNotExist())
      .andExpect(jsonPath("$.items[0].status").doesNotExist())
      .andReturn().getResponse().getContentAsString();

    assertThat(body).doesNotContain(IBAN);
  }

  @Test
  void returns200WithAnEmptyList() throws Exception {
    list(new RecordingService(emptyPage()), get("/transfers").param("iban", IBAN))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items").isArray())
      .andExpect(jsonPath("$.items").isEmpty())
      .andExpect(jsonPath("$.page").value(0))
      .andExpect(jsonPath("$.size").value(20))
      .andExpect(jsonPath("$.totalElements").value(0))
      .andExpect(jsonPath("$.hasNext").value(false));
  }

  @Test
  void badRequestBodyDoesNotEchoTheIban() throws Exception {
    TransferListServiceDto service = (iban, direction, statuses, type, from, to, page, size) -> {
      throw new IllegalArgumentException("direction must be ALL, OUTGOING or INCOMING");
    };

    String body = list(service, get("/transfers").param("iban", IBAN).param("direction", "SENT"))
      .andExpect(status().isBadRequest())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
      .andExpect(jsonPath("$.status").value(400))
      .andExpect(jsonPath("$.detail").value("direction must be ALL, OUTGOING or INCOMING"))
      .andExpect(jsonPath("$.instance").value("/transfers"))
      .andReturn().getResponse().getContentAsString();

    assertThat(body).doesNotContain(IBAN).doesNotContain("0542811101");
  }

  @Test
  void getByIdIsStillServedByTheOtherController() throws Exception {
    RecordingService listService = new RecordingService(emptyPage());
    List<String> receivedIds = new ArrayList<>();
    TransferQueryServiceDto queryService = id -> {
      receivedIds.add(id);
      return new TransferDetailsResponseDto(id, COUNTERPARTY_MASKED, COUNTERPARTY_MASKED, new BigDecimal("1.00"),
        "EUR", "COMPLETED", List.of());
    };
    List<String> postedBodies = new ArrayList<>();
    TransferServiceDto transferService = request -> {
      postedBodies.add(request.toString());
      return new TransferResponseDto("t-new", "COMPLETED", List.of());
    };
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TransferListController(listService),
        new TransferQueryController(queryService), new TransferController(transferService))
      .setControllerAdvice(new WebExceptionHandler())
      .build();

    mockMvc.perform(get("/transfers/{id}", "demo-ext-001"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.transferId").value("demo-ext-001"));
    mockMvc.perform(get("/transfers").param("iban", IBAN))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.items").isArray());
    mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(
        "{\"sourceIban\":\"" + IBAN + "\",\"destIban\":\"IT60X0542811101000000000142\","
          + "\"amount\":10.00,\"currency\":\"EUR\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.transferId").value("t-new"));

    assertThat(receivedIds).containsExactly("demo-ext-001");
    assertThat(listService.calls).hasSize(1);
    assertThat(postedBodies).hasSize(1);
  }
}
