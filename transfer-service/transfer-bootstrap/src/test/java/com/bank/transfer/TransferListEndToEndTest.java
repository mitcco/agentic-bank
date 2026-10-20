package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * GET /transfers?iban=... from the HTTP layer down to the real H2 database: the seeded demo data (internal rows get
 * created_at from the database default, in local time of the JVM, external rows from the application, in UTC) and
 * transfers created through POST /transfers. The fraud check is a hand written fake that always allows. Dedicated
 * in-memory H2 and Spring context, schedulers off.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:transferlist;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "clearing.outcomes.enabled=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class TransferListEndToEndTest {

  private static final FraudCheckResultDto ALLOWED = new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());

  @TestConfiguration
  static class AlwaysAllowedFraudCheck {

    @Bean
    @Primary
    FraudCheckOutPort alwaysAllowedFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) -> ALLOWED;
    }
  }

  /** demo-acc-141: sender of demo-ext-001 (external) and receiver of demo-tr-034 (internal, from demo-acc-134). */
  private static final String ACCOUNT_141 = "IT60X0542811101000000000141";
  private static final String ACCOUNT_134 = "IT60X0542811101000000000134";
  private static final String DEMO_EXTERNAL_DEST = "IT60X0306911101000000000001";
  /** ac1: sender of tr1 (COMPLETED, to ac2) and receiver of tr3 (REJECTED, from ac3). */
  private static final String AC1 = "IT60X0542811101000000000001";
  private static final String AC2 = "IT60X0542811101000000000002";
  private static final String AC3 = "IT60X0542811101000000000003";
  /** ABI 05428: an account of this bank. */
  private static final String IBAN_PREFIX = "IT60X0542811101000000";
  /** ABI 03069: another bank. */
  private static final String EXTERNAL_IBAN = "IT60X0306909606100000012345";
  private static final String UNKNOWN_IBAN = "IT60X0306909606100000099999";
  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private String source;

  @BeforeEach
  void setUp() {
    int number = SEQUENCE.incrementAndGet();
    source = IBAN_PREFIX + String.format("L%05d", number);
    createAccount("e2e-list-" + number, source, "1000");
  }

  // ------------------------------------------------------------------ helpers

  private void createAccount(String id, String iban, String balance) {
    accountRepository.save(new Account(id, new Iban(iban), new Money(new BigDecimal(balance), "EUR")));
  }

  private static String masked(String iban) {
    return iban.substring(0, 4) + "*".repeat(iban.length() - 8) + iban.substring(iban.length() - 4);
  }

  /** GET /transfers with the iban (if not null) and the other parameters given as name, value, name, value... */
  private MvcResult list(String iban, String... nameValuePairs) throws Exception {
    MockHttpServletRequestBuilder request = get("/transfers");
    if (iban != null) {
      request.param("iban", iban);
    }
    for (int i = 0; i < nameValuePairs.length; i += 2) {
      request.param(nameValuePairs[i], nameValuePairs[i + 1]);
    }
    return mockMvc.perform(request).andReturn();
  }

  private MvcResult postTransfer(String sourceIban, String destIban, String amount) throws Exception {
    String body = "{\"sourceIban\":\"" + sourceIban + "\",\"destIban\":\"" + destIban + "\","
      + "\"amount\":" + amount + ",\"currency\":\"EUR\"}";
    return mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  private static List<Map<String, Object>> items(MvcResult result) throws Exception {
    return JsonPath.read(body(result), "$.items");
  }

  private static List<String> idsOf(MvcResult result) throws Exception {
    return items(result).stream().map(item -> (String) item.get("transferId")).toList();
  }

  private static Map<String, Object> itemOf(MvcResult result, String transferId) throws Exception {
    return items(result).stream().filter(item -> transferId.equals(item.get("transferId"))).findFirst()
      .orElseThrow(() -> new AssertionError("item not found: " + transferId));
  }

  private static BigDecimal amountOf(Map<String, Object> item) {
    return new BigDecimal(String.valueOf(item.get("amount")));
  }

  private static Number number(MvcResult result, String path) throws Exception {
    return JsonPath.read(body(result), path);
  }

  private static boolean flag(MvcResult result, String path) throws Exception {
    return JsonPath.read(body(result), path);
  }

  // ------------------------------------------------------------------ seeded demo account 141

  @Test
  void seededAccount141ListsAnExternalOutgoingAndAnInternalIncoming() throws Exception {
    MvcResult result = list(ACCOUNT_141);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    // created in the same instant by the seed: the order is not asserted
    assertThat(idsOf(result)).containsExactlyInAnyOrder("demo-ext-001", "demo-tr-034");
    Map<String, Object> external = itemOf(result, "demo-ext-001");
    assertThat(external.get("type")).isEqualTo("EXTERNAL");
    assertThat(external.get("direction")).isEqualTo("OUTGOING");
    assertThat(external.get("transferStatus")).isEqualTo("COMPLETED");
    assertThat(amountOf(external)).isEqualByComparingTo("59.73");
    assertThat(external.get("currency")).isEqualTo("EUR");
    assertThat(external.get("counterpartyIbanMasked")).isEqualTo("IT60" + "*".repeat(19) + "0001");
    Map<String, Object> internal = itemOf(result, "demo-tr-034");
    assertThat(internal.get("type")).isEqualTo("INTERNAL");
    assertThat(internal.get("direction")).isEqualTo("INCOMING");
    assertThat(internal.get("transferStatus")).isEqualTo("COMPLETED");
    assertThat(amountOf(internal)).isEqualByComparingTo("32.46");
    assertThat(internal.get("currency")).isEqualTo("EUR");
    assertThat(internal.get("counterpartyIbanMasked")).isEqualTo("IT60" + "*".repeat(19) + "0134");
    assertThat(number(result, "$.page").intValue()).isZero();
    assertThat(number(result, "$.size").intValue()).isEqualTo(20);
    assertThat(number(result, "$.totalElements").longValue()).isEqualTo(2L);
    assertThat(flag(result, "$.hasNext")).isFalse();
  }

  @Test
  void theCreatedAtOfTheSeededRowsIsAnIsoInstantNearNowOnBothTables() throws Exception {
    MvcResult result = list(ACCOUNT_141);

    for (String id : List.of("demo-ext-001", "demo-tr-034")) {
      Instant createdAt = Instant.parse((String) itemOf(result, id).get("createdAt"));
      // the seed ran when the context started: a wrong time zone would be off by hours
      assertThat(Duration.between(createdAt, Instant.now()).abs()).as(id).isLessThan(Duration.ofMinutes(30));
    }
  }

  @Test
  void seededAccount141ResponseNeverContainsAFullIban() throws Exception {
    String text = body(list(ACCOUNT_141));

    assertThat(text)
      .doesNotContain(ACCOUNT_141)
      .doesNotContain(ACCOUNT_134)
      .doesNotContain(DEMO_EXTERNAL_DEST)
      .doesNotContain("0542811101");
  }

  @Test
  void directionFilterOnTheSeededAccount() throws Exception {
    MvcResult outgoing = list(ACCOUNT_141, "direction", "OUTGOING");
    MvcResult incoming = list(ACCOUNT_141, "direction", "INCOMING");
    MvcResult all = list(ACCOUNT_141, "direction", "ALL");

    assertThat(idsOf(outgoing)).containsExactly("demo-ext-001");
    assertThat(number(outgoing, "$.totalElements").longValue()).isEqualTo(1L);
    assertThat(idsOf(incoming)).containsExactly("demo-tr-034");
    assertThat(number(incoming, "$.totalElements").longValue()).isEqualTo(1L);
    assertThat(idsOf(all)).containsExactlyInAnyOrder("demo-ext-001", "demo-tr-034");
  }

  @Test
  void typeFilterOnTheSeededAccount() throws Exception {
    assertThat(idsOf(list(ACCOUNT_141, "type", "INTERNAL"))).containsExactly("demo-tr-034");
    assertThat(idsOf(list(ACCOUNT_141, "type", "EXTERNAL"))).containsExactly("demo-ext-001");
  }

  @Test
  void theIbanIsAcceptedWrittenInADifferentWay() throws Exception {
    MvcResult result = list(" it60 x054 2811 1010 0000 0000 141 ");

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(idsOf(result)).containsExactlyInAnyOrder("demo-ext-001", "demo-tr-034");
    assertThat(itemOf(result, "demo-ext-001").get("direction")).isEqualTo("OUTGOING");
    assertThat(itemOf(result, "demo-tr-034").get("direction")).isEqualTo("INCOMING");
  }

  @Test
  void dateFiltersOnTheSeededAccount() throws Exception {
    assertThat(idsOf(list(ACCOUNT_141, "from", "2000-01-01T00:00:00Z")))
      .containsExactlyInAnyOrder("demo-ext-001", "demo-tr-034");
    assertThat(idsOf(list(ACCOUNT_141, "to", "2099-01-01T00:00:00Z")))
      .containsExactlyInAnyOrder("demo-ext-001", "demo-tr-034");
    assertThat(idsOf(list(ACCOUNT_141, "from", "2099-01-01T00:00:00Z"))).isEmpty();
    assertThat(idsOf(list(ACCOUNT_141, "to", "2000-01-01T00:00:00Z"))).isEmpty();
    MvcResult emptyByDate = list(ACCOUNT_141, "from", "2099-01-01T00:00:00Z");
    assertThat(emptyByDate.getResponse().getStatus()).isEqualTo(200);
    assertThat(number(emptyByDate, "$.totalElements").longValue()).isZero();
  }

  @Test
  void paginationOnTheSeededAccount() throws Exception {
    MvcResult first = list(ACCOUNT_141, "size", "1");
    MvcResult second = list(ACCOUNT_141, "page", "1", "size", "1");
    MvcResult third = list(ACCOUNT_141, "page", "2", "size", "1");

    assertThat(items(first)).hasSize(1);
    assertThat(number(first, "$.totalElements").longValue()).isEqualTo(2L);
    assertThat(number(first, "$.size").intValue()).isEqualTo(1);
    assertThat(flag(first, "$.hasNext")).isTrue();
    assertThat(items(second)).hasSize(1);
    assertThat(number(second, "$.page").intValue()).isEqualTo(1);
    assertThat(flag(second, "$.hasNext")).isFalse();
    assertThat(items(third)).isEmpty();
    assertThat(number(third, "$.totalElements").longValue()).isEqualTo(2L);
    assertThat(List.of(idsOf(first).get(0), idsOf(second).get(0)))
      .containsExactlyInAnyOrder("demo-ext-001", "demo-tr-034");
  }

  // ------------------------------------------------------------------ seeded ac1

  @Test
  void statusFilterOnAc1() throws Exception {
    MvcResult rejected = list(AC1, "status", "REJECTED");
    MvcResult completed = list(AC1, "status", "COMPLETED");
    MvcResult both = list(AC1, "status", "COMPLETED", "status", "REJECTED");
    MvcResult none = list(AC1);

    assertThat(idsOf(rejected)).containsExactly("tr3");
    Map<String, Object> tr3 = itemOf(rejected, "tr3");
    assertThat(tr3.get("direction")).isEqualTo("INCOMING");
    assertThat(tr3.get("type")).isEqualTo("INTERNAL");
    assertThat(tr3.get("transferStatus")).isEqualTo("REJECTED");
    assertThat(tr3.get("counterpartyIbanMasked")).isEqualTo(masked(AC3));
    assertThat(amountOf(tr3)).isEqualByComparingTo("15000.00");
    assertThat(idsOf(completed)).containsExactly("tr1");
    Map<String, Object> tr1 = itemOf(completed, "tr1");
    assertThat(tr1.get("direction")).isEqualTo("OUTGOING");
    assertThat(tr1.get("counterpartyIbanMasked")).isEqualTo(masked(AC2));
    assertThat(amountOf(tr1)).isEqualByComparingTo("100.00");
    assertThat(idsOf(both)).containsExactlyInAnyOrder("tr1", "tr3");
    assertThat(idsOf(none)).containsExactlyInAnyOrder("tr1", "tr3");
  }

  @Test
  void aStatusWithNoTransfersGivesAnEmptyList() throws Exception {
    MvcResult pending = list(AC1, "status", "PENDING");

    assertThat(pending.getResponse().getStatus()).isEqualTo(200);
    assertThat(items(pending)).isEmpty();
    assertThat(number(pending, "$.totalElements").longValue()).isZero();
  }

  // ------------------------------------------------------------------ new transfers

  @Test
  void newTransferAppearsFirst() throws Exception {
    String dest = IBAN_PREFIX + String.format("M%05d", SEQUENCE.get());
    createAccount("e2e-list-dest-" + SEQUENCE.get(), dest, "0");
    MvcResult sent = postTransfer(source, EXTERNAL_IBAN, "50.00");
    assertThat(sent.getResponse().getStatus()).isEqualTo(202);
    String externalId = JsonPath.read(body(sent), "$.transferId");
    // the external transfer is made one hour older, so that the order does not depend on the clock resolution
    Instant oneHourAgo = Instant.now().minus(Duration.ofHours(1));
    jdbcTemplate.update("update external_transfer set created_at = ? where id = ?",
      Timestamp.valueOf(LocalDateTime.ofInstant(oneHourAgo, ZoneOffset.UTC)), externalId);
    MvcResult internalPost = postTransfer(source, dest, "123.45");
    assertThat(internalPost.getResponse().getStatus()).isEqualTo(200);
    String internalId = JsonPath.read(body(internalPost), "$.transferId");

    MvcResult result = list(source);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(idsOf(result)).containsExactly(internalId, externalId);
    Map<String, Object> internal = itemOf(result, internalId);
    assertThat(internal.get("type")).isEqualTo("INTERNAL");
    assertThat(internal.get("direction")).isEqualTo("OUTGOING");
    assertThat(internal.get("transferStatus")).isEqualTo("COMPLETED");
    assertThat(internal.get("counterpartyIbanMasked")).isEqualTo(masked(dest));
    assertThat(amountOf(internal)).isEqualByComparingTo("123.45");
    Map<String, Object> external = itemOf(result, externalId);
    assertThat(external.get("type")).isEqualTo("EXTERNAL");
    assertThat(external.get("transferStatus")).isEqualTo("SENT");
    assertThat(external.get("counterpartyIbanMasked")).isEqualTo(masked(EXTERNAL_IBAN));
    Instant internalCreatedAt = Instant.parse((String) internal.get("createdAt"));
    Instant externalCreatedAt = Instant.parse((String) external.get("createdAt"));
    assertThat(Duration.between(internalCreatedAt, Instant.now()).abs()).isLessThan(Duration.ofMinutes(5));
    assertThat(Duration.between(externalCreatedAt, oneHourAgo).abs()).isLessThan(Duration.ofSeconds(2));
    assertThat(internalCreatedAt).isAfter(externalCreatedAt);

    MvcResult destination = list(dest);
    assertThat(idsOf(destination)).containsExactly(internalId);
    assertThat(itemOf(destination, internalId).get("direction")).isEqualTo("INCOMING");
    assertThat(itemOf(destination, internalId).get("counterpartyIbanMasked")).isEqualTo(masked(source));
  }

  @Test
  void anExternalIbanListsTheExternalTransfersItReceived() throws Exception {
    String externalDest = "IT60X0306909606100000077" + String.format("%03d", SEQUENCE.get());
    MvcResult sent = postTransfer(source, externalDest, "20.00");
    assertThat(sent.getResponse().getStatus()).isEqualTo(202);
    String externalId = JsonPath.read(body(sent), "$.transferId");

    MvcResult result = list(externalDest);

    assertThat(idsOf(result)).containsExactly(externalId);
    assertThat(itemOf(result, externalId).get("direction")).isEqualTo("INCOMING");
    assertThat(itemOf(result, externalId).get("counterpartyIbanMasked")).isEqualTo(masked(source));
  }

  // ------------------------------------------------------------------ errors and confidentiality

  static Stream<Arguments> invalidParameters() {
    return Stream.of(
      Arguments.of(new String[] {"direction", "SENT"}, "direction must be ALL, OUTGOING or INCOMING"),
      Arguments.of(new String[] {"status", "BOGUS"}, "status is not valid"),
      Arguments.of(new String[] {"type", "OTHER"}, "type must be INTERNAL or EXTERNAL"),
      Arguments.of(new String[] {"from", "yesterday"}, "from and to must be ISO-8601 instants"),
      Arguments.of(new String[] {"to", "2026-10-19"}, "from and to must be ISO-8601 instants"),
      Arguments.of(new String[] {"from", "2026-10-19T00:00:00Z", "to", "2026-10-18T00:00:00Z"},
        "from must be before to"),
      Arguments.of(new String[] {"page", "-1"}, "page must be zero or more"),
      Arguments.of(new String[] {"size", "500"}, "size must be between 1 and 100"),
      Arguments.of(new String[] {"size", "0"}, "size must be between 1 and 100"),
      Arguments.of(new String[] {"page", "10", "size", "100"}, "page window too large"));
  }

  @ParameterizedTest
  @MethodSource("invalidParameters")
  void invalidParametersAnswer400WithoutTheIban(String[] parameters, String expectedDetail) throws Exception {
    MvcResult result = list(ACCOUNT_141, parameters);

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getContentType()).contains("problem+json");
    assertThat(JsonPath.<String>read(body(result), "$.detail")).isEqualTo(expectedDetail);
    assertThat(JsonPath.<String>read(body(result), "$.instance")).isEqualTo("/transfers");
    assertThat(body(result)).doesNotContain(ACCOUNT_141).doesNotContain("0542811101");
  }

  @Test
  void aMissingIbanAnswers400() throws Exception {
    MvcResult result = list(null);

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(JsonPath.<String>read(body(result), "$.detail")).isEqualTo("iban is required");
  }

  @Test
  void anInvalidIbanAnswers400WithoutEchoingIt() throws Exception {
    String malformed = "IT60-NOT-AN-IBAN";
    MvcResult result = list(malformed);

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(body(result)).doesNotContain(malformed);
  }

  @Test
  void unknownIbanAnswers200Empty() throws Exception {
    MvcResult result = list(UNKNOWN_IBAN);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(items(result)).isEmpty();
    assertThat(number(result, "$.totalElements").longValue()).isZero();
    assertThat(flag(result, "$.hasNext")).isFalse();
    assertThat(body(result)).doesNotContain(UNKNOWN_IBAN);
  }

  @Test
  void logsNeverContainIbansNorAmounts(CapturedOutput output) throws Exception {
    // unusual amount, so that unrelated log lines cannot match by chance
    String distinctiveAmount = "217.31";
    String dest = IBAN_PREFIX + String.format("M%05d", SEQUENCE.get());
    createAccount("e2e-list-dest-" + SEQUENCE.get(), dest, "0");
    postTransfer(source, EXTERNAL_IBAN, distinctiveAmount);
    postTransfer(source, dest, distinctiveAmount);

    list(source);
    list(dest);
    list(ACCOUNT_141);
    list(AC1, "status", "REJECTED");
    list(UNKNOWN_IBAN);
    list(ACCOUNT_141, "direction", "SENT");
    list(ACCOUNT_141, "size", "500");
    list(null);
    list("IT60-NOT-AN-IBAN");

    assertThat(output.getAll())
      .doesNotContain(source)
      .doesNotContain(dest)
      .doesNotContain(EXTERNAL_IBAN)
      .doesNotContain(ACCOUNT_141)
      .doesNotContain(ACCOUNT_134)
      .doesNotContain(AC1)
      .doesNotContain(UNKNOWN_IBAN)
      .doesNotContain("IT60-NOT-AN-IBAN")
      .doesNotContain(distinctiveAmount);
  }
}
