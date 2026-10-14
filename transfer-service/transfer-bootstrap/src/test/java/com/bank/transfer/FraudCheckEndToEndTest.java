package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Whole application on in-memory H2 (no Docker) with a fake Fraud service on a real socket (JDK HttpServer).
 * Every test creates its own accounts, so the tests do not depend on each other.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:fraude2e;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "fraud.read-timeout=500ms"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class FraudCheckEndToEndTest {

  private static final String IBAN_PREFIX = "IT60X0542811101000000";
  private static final String AMOUNT = "123.45";
  private static final String FIXED_DETAIL = "Fraud check is temporarily unavailable: the transfer was not executed";
  private static final AtomicInteger SEQUENCE = new AtomicInteger();
  private static final FakeFraud FRAUD = FakeFraud.start();

  @DynamicPropertySource
  static void fraudProperties(DynamicPropertyRegistry registry) {
    registry.add("fraud.base-url", FRAUD::baseUrl);
  }

  @AfterAll
  static void stopFraud() {
    FRAUD.stop();
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private String source;
  private String dest;

  @BeforeEach
  void setUp() throws IOException {
    FRAUD.ensureRunning();
    FRAUD.reset();
    int n = SEQUENCE.incrementAndGet();
    source = IBAN_PREFIX + String.format("E%05d", n * 2);
    dest = IBAN_PREFIX + String.format("E%05d", n * 2 + 1);
    createAccount("e-" + n + "-s", source, "1000");
    createAccount("e-" + n + "-d", dest, "0");
  }

  // ------------------------------------------------------------------ helpers

  private void createAccount(String id, String iban, String balance) {
    accountRepository.save(new Account(id, new Iban(iban), new Money(new BigDecimal(balance), "EUR")));
  }

  private BigDecimal balanceOf(String iban) {
    return accountRepository.findByIban(new Iban(iban)).orElseThrow().money().amount();
  }

  private int transferRowsOf(String iban) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from transfer where source_iban = ?",
      Integer.class, iban);
    return count == null ? 0 : count;
  }

  private MvcResult postTransfer() throws Exception {
    String body = "{\"sourceIban\":\"" + source + "\",\"destIban\":\"" + dest + "\","
      + "\"amount\":" + AMOUNT + ",\"currency\":\"EUR\"}";
    return mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
  }

  private MvcResult postTransfer(String amount) throws Exception {
    String body = "{\"sourceIban\":\"" + source + "\",\"destIban\":\"" + dest + "\","
      + "\"amount\":" + amount + ",\"currency\":\"EUR\"}";
    return mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
  }

  private List<String> outboxPayloadsFor(String transferId) {
    return jdbcTemplate.queryForList("select payload from outbox where transfer_id = ?", String.class, transferId);
  }

  private int outboxRowsMentioning(String iban) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from outbox where payload like ?",
      Integer.class, "%\"sourceIban\":\"" + iban + "\"%");
    return count == null ? 0 : count;
  }

  private MvcResult postTransfer(String sourceIban, String destIban, String amount) throws Exception {
    String body = "{\"sourceIban\":\"" + sourceIban + "\",\"destIban\":\"" + destIban + "\","
      + "\"amount\":" + amount + ",\"currency\":\"EUR\"}";
    return mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
  }

  private static String allowedFor(String requestBody) {
    return "{\"transferId\":\"" + transferIdOf(requestBody) + "\",\"status\":\"ALLOWED\"}";
  }

  private static String transferIdOf(String requestBody) {
    Matcher matcher = Pattern.compile("\"transferId\"\\s*:\\s*\"([^\"]+)\"").matcher(requestBody);
    assertThat(matcher.find()).as("transferId in the request sent to the Fraud: %s", requestBody).isTrue();
    return matcher.group(1);
  }

  // ------------------------------------------------------------------ T16, T17

  @Test
  void t16_allowedMakesTheTransferCompleteAndMovesTheMoney() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, allowedFor(request)));

    MvcResult result = postTransfer();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    String response = result.getResponse().getContentAsString();
    assertThat(response).contains("\"transferStatus\":\"COMPLETED\"").contains("\"reasons\":[]");

    assertThat(balanceOf(source)).isEqualByComparingTo("876.55");
    assertThat(balanceOf(dest)).isEqualByComparingTo("123.45");

    assertThat(FRAUD.requests()).hasSize(1);
    String sent = FRAUD.requests().get(0);
    String returnedId = jdbcTemplate.queryForObject("select id from transfer where source_iban = ?", String.class, source);
    assertThat(transferIdOf(sent)).isEqualTo(returnedId);
    assertThat(response).contains("\"transferId\":\"" + returnedId + "\"");
    assertThat(sent).contains("\"destinationIban\":\"" + dest + "\"").contains("\"sourceIban\":\"" + source + "\"")
      .doesNotContain("\"destIban\"")
      .containsPattern("\"amount\":123\\.45[,}]")
      .contains("\"currency\":\"EUR\"");
  }

  @Test
  void t17_rejectedWithReasonsIsSavedAsRejectedAndTheMoneyDoesNotMove() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, "{\"transferId\":\"" + transferIdOf(request)
      + "\",\"status\":\"REJECTED\",\"reasons\":[\"Source IBAN is locked\",\"Destination IBAN is locked\"]}"));

    MvcResult result = postTransfer();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    String response = result.getResponse().getContentAsString();
    assertThat(response).contains("\"transferStatus\":\"REJECTED\"")
      .contains("\"reasons\":[\"Source IBAN is locked\",\"Destination IBAN is locked\"]");

    assertThat(jdbcTemplate.queryForObject("select status from transfer where source_iban = ?", String.class, source))
      .isEqualTo("REJECTED");
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
    assertThat(balanceOf(dest)).isEqualByComparingTo("0");
  }

  // ------------------------------------------------------------------ T18 to T23

  record Scenario(String name, Consumer<FakeFraud> setup) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Scenario> unavailableScenarios() {
    return Stream.of(
      new Scenario("t18 Fraud answers 500", f -> f.respondWith(r -> FakeFraud.json(500,
        "{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\"}"))),
      new Scenario("t19 Fraud answers after 1s (read timeout is 500 ms)", f -> f.respondWith(r -> {
        FakeFraud.sleep(1000);
        return FakeFraud.json(200, allowedFor(r));
      })),
      new Scenario("t20 Fraud answers 200 with an unknown status", f -> f.respondWith(r -> FakeFraud.json(200,
        "{\"transferId\":\"" + transferIdOf(r) + "\",\"status\":\"BLOCKED\"}"))),
      new Scenario("t21 Fraud answers REJECTED without reasons", f -> f.respondWith(r -> FakeFraud.json(200,
        "{\"transferId\":\"" + transferIdOf(r) + "\",\"status\":\"REJECTED\"}"))),
      new Scenario("t22 Fraud is down (connection refused)", FakeFraud::stop));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unavailableScenarios")
  void t18_to_t22_whenTheFraudCheckIsUnavailableTheApiAnswers503AndNothingIsSaved(Scenario scenario,
    CapturedOutput output) throws Exception {
    scenario.setup().accept(FRAUD);

    MvcResult result = postTransfer();

    assertThat(result.getResponse().getStatus()).isEqualTo(503);
    assertThat(transferRowsOf(source)).isZero();
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
    assertThat(balanceOf(dest)).isEqualByComparingTo("0");

    // t23: neither the response nor the logs reveal IBANs or amounts
    String body = result.getResponse().getContentAsString();
    assertThat(body).contains(FIXED_DETAIL).doesNotContain(source).doesNotContain(dest).doesNotContain(AMOUNT);
    assertThat(output.getAll()).doesNotContain(source).doesNotContain(dest).doesNotContain(AMOUNT);
  }

  @Test
  void t18_theFixedProblemDetailIsTheOneOfThePlan() throws Exception {
    FRAUD.respondWith(r -> FakeFraud.json(500, "{\"code\":\"INTERNAL_ERROR\",\"message\":\"boom\"}"));

    String requestBody = "{\"sourceIban\":\"" + source + "\",\"destIban\":\"" + dest + "\","
      + "\"amount\":" + AMOUNT + ",\"currency\":\"EUR\"}";
    mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(requestBody))
      .andExpect(status().isServiceUnavailable())
      .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
      .andExpect(jsonPath("$.status").value(503))
      .andExpect(jsonPath("$.title").value("Service Unavailable"))
      .andExpect(jsonPath("$.detail").value(FIXED_DETAIL));
  }

  // ------------------------------------------------------------------ T24, T25

  @Test
  void t24_amountWithMoreThanTwoDecimalsAnswers400AndNothingIsAskedOrSaved() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, allowedFor(request)));

    MvcResult result = postTransfer("1.234");

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("\"status\":400")
      .contains("\"detail\":\"Transfer amount can have at most 2 decimals\"")
      .doesNotContain("1.234").doesNotContain(source).doesNotContain(dest);
    assertThat(FRAUD.requests()).isEmpty();
    assertThat(transferRowsOf(source)).isZero();
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
    assertThat(balanceOf(dest)).isEqualByComparingTo("0");
  }

  @Test
  void t25_amountWithTrailingZerosIsAcceptedAndCompleted() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, allowedFor(request)));

    MvcResult result = postTransfer("10.5000");

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(result.getResponse().getContentAsString()).contains("\"transferStatus\":\"COMPLETED\"");
    assertThat(FRAUD.requests()).hasSize(1);
    assertThat(balanceOf(source)).isEqualByComparingTo("989.50");
    assertThat(balanceOf(dest)).isEqualByComparingTo("10.50");
  }

  // ------------------------------------------------------------------ T26 to T32: outbox rows (contract scenarios 11, 12)

  @Test
  void t26_completedTransferWritesExactlyOneOutboxRowWithTheContractPayload() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, allowedFor(request)));
    Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    MvcResult result = postTransfer();

    Instant after = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    String response = result.getResponse().getContentAsString();
    assertThat(response).contains("\"transferStatus\":\"COMPLETED\"");
    String transferId = transferIdOf(response);

    List<String> payloads = outboxPayloadsFor(transferId);
    assertThat(payloads).hasSize(1);
    String payload = payloads.get(0);
    assertThat(payload).contains("\"transferId\":\"" + transferId + "\"")
      .contains("\"sourceIban\":\"" + source + "\"")
      .contains("\"destinationIban\":\"" + dest + "\"")
      .contains("\"currency\":\"EUR\"")
      .contains("\"status\":\"COMPLETED\"")
      .containsPattern("\"amount\":123\\.45[,}]")
      .doesNotContain("\"reasons\"")
      .doesNotContain("\"destIban\"");

    Matcher occurredAt = Pattern.compile("\"occurredAt\":\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)\"").matcher(payload);
    assertThat(occurredAt.find()).as("occurredAt in the payload: %s", payload).isTrue();
    Instant concluded = Instant.parse(occurredAt.group(1));
    assertThat(concluded).isBetween(before, after);
    assertThat(outboxRowsMentioning(source)).isEqualTo(1);
  }

  @Test
  void t27_rejectedTransferWritesExactlyOneOutboxRowWithTheReasons() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, "{\"transferId\":\"" + transferIdOf(request)
      + "\",\"status\":\"REJECTED\",\"reasons\":[\"Source IBAN is locked\",\"Destination IBAN is locked\"]}"));

    MvcResult result = postTransfer();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    String response = result.getResponse().getContentAsString();
    assertThat(response).contains("\"transferStatus\":\"REJECTED\"");
    String transferId = transferIdOf(response);

    List<String> payloads = outboxPayloadsFor(transferId);
    assertThat(payloads).hasSize(1);
    assertThat(payloads.get(0)).contains("\"status\":\"REJECTED\"")
      .contains("\"reasons\":[\"Source IBAN is locked\",\"Destination IBAN is locked\"]")
      .contains("\"sourceIban\":\"" + source + "\"")
      .contains("\"destinationIban\":\"" + dest + "\"");
    assertThat(outboxRowsMentioning(source)).isEqualTo(1);
  }

  /** Chosen: all the unavailability scenarios (t18 to t22), one extra second for t19 only. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("unavailableScenarios")
  void t28_whenTheFraudCheckIsUnavailableNoOutboxRowIsWritten(Scenario scenario) throws Exception {
    scenario.setup().accept(FRAUD);

    MvcResult result = postTransfer();

    assertThat(result.getResponse().getStatus()).isEqualTo(503);
    assertThat(outboxRowsMentioning(source)).isZero();
    assertThat(transferRowsOf(source)).isZero();
  }

  @Test
  void t29_missingDestinationAccountAnswers404WritesNoOutboxRowAndNeverAsksTheFraudCheck() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, allowedFor(request)));
    String neverCreated = IBAN_PREFIX + String.format("N%05d", SEQUENCE.get());

    MvcResult result = postTransfer(source, neverCreated, AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(outboxRowsMentioning(source)).isZero();
    assertThat(transferRowsOf(source)).isZero();
    assertThat(FRAUD.requests()).isEmpty();
  }

  @Test
  void t30_amountWithMoreThanTwoDecimalsAnswers400AndWritesNoOutboxRow() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, allowedFor(request)));

    MvcResult result = postTransfer("1.234");

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(outboxRowsMentioning(source)).isZero();
  }

  @Test
  void t31_insufficientFundsAnswers422WritesNoOutboxRowAndSavesNothing() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, allowedFor(request)));

    MvcResult result = postTransfer("2000");

    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(outboxRowsMentioning(source)).isZero();
    assertThat(transferRowsOf(source)).isZero();
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
    assertThat(balanceOf(dest)).isEqualByComparingTo("0");
  }

  @Test
  void t32_rejectedTransferWithALowercaseCurrencyAnswers400AndNothingIsSavedNorNotified() throws Exception {
    FRAUD.respondWith(request -> FakeFraud.json(200, "{\"transferId\":\"" + transferIdOf(request)
      + "\",\"status\":\"REJECTED\",\"reasons\":[\"Source IBAN is locked\"]}"));
    String body = "{\"sourceIban\":\"" + source + "\",\"destIban\":\"" + dest + "\","
      + "\"amount\":" + AMOUNT + ",\"currency\":\"eur\"}";

    MvcResult result = mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
      .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getContentAsString()).contains("currency must be three uppercase letters");
    assertThat(FRAUD.requests()).hasSize(1);
    assertThat(transferRowsOf(source)).isZero();
    assertThat(outboxRowsMentioning(source)).isZero();
  }

  // ------------------------------------------------------------------ the fake Fraud service

  /** A Fraud service on a real socket whose answer each test decides; it records the requests it receives. */
  static final class FakeFraud {

    record Reply(int status, String body) {
    }

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Function<String, Reply> responder = request -> json(500, "{}");
    private HttpServer server;
    private int port;

    static FakeFraud start() {
      try {
        FakeFraud fraud = new FakeFraud();
        fraud.server = fraud.create(0);
        fraud.port = fraud.server.getAddress().getPort();
        return fraud;
      } catch (IOException e) {
        throw new IllegalStateException("Cannot start the fake Fraud service", e);
      }
    }

    private HttpServer create(int portToUse) throws IOException {
      HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), portToUse), 0);
      created.createContext("/fraud-check", exchange -> {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(body);
        Reply reply;
        try {
          reply = responder.apply(body);
        } catch (RuntimeException | AssertionError e) {
          reply = json(500, "{}");
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
          exchange.sendResponseHeaders(reply.status(), bytes.length);
          exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
          // the client may have given up (read timeout): nothing to do
        } finally {
          exchange.close();
        }
      });
      // a slow answer must not block the next request: handlers run on their own threads
      created.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
      created.start();
      return created;
    }

    String baseUrl() {
      return "http://127.0.0.1:" + port;
    }

    synchronized void ensureRunning() throws IOException {
      if (server == null) {
        server = create(port);
      }
    }

    synchronized void stop() {
      if (server != null) {
        server.stop(0);
        server = null;
      }
    }

    void reset() {
      requests.clear();
      responder = request -> json(500, "{}");
    }

    void respondWith(Function<String, Reply> newResponder) {
      this.responder = newResponder;
    }

    List<String> requests() {
      return requests;
    }

    static Reply json(int status, String body) {
      return new Reply(status, body);
    }

    static void sleep(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
