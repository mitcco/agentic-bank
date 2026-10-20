package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.port.in.ProcessClearingOutcomesInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingHouseOutPortImpl;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingModeEnum;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * GET /transfers/{id} from the HTTP layer down to the real H2 database: internal and external transfers created
 * through POST /transfers (clearing house simulated, processOutcomes() called by hand) and the seeded demo data.
 * The fraud check is a hand written fake that always allows. Dedicated in-memory H2 and Spring context,
 * schedulers off (the default).
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:transferquery;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password=",
  "clearing.outcomes.enabled=false"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class TransferQueryEndToEndTest {

  private static final FraudCheckResultDto ALLOWED = new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());

  @TestConfiguration
  static class AlwaysAllowedFraudCheck {

    @Bean
    @Primary
    FraudCheckOutPort alwaysAllowedFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) -> ALLOWED;
    }
  }

  /** ABI 05428: an account of this bank. */
  private static final String IBAN_PREFIX = "IT60X0542811101000000";
  /** ABI 03069: another bank. */
  private static final String EXTERNAL_IBAN = "IT60X0306909606100000012345";
  private static final String SEEDED_SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String AMOUNT = "123.45";
  private static final String PROBLEM_DETAIL = "Transfer not found";
  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProcessClearingOutcomesInPort processClearingOutcomes;

  @Autowired
  private SimulatedClearingHouseOutPortImpl simulator;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  private String source;

  @BeforeEach
  void setUp() {
    // the simulator is a singleton: back to ACCEPT and with nothing left in its queue
    simulator.setMode(SimulatedClearingModeEnum.ACCEPT);
    simulator.pollOutcomes();

    int number = SEQUENCE.incrementAndGet();
    source = IBAN_PREFIX + String.format("Q%05d", number);
    createAccount("e2e-query-" + number, source, "1000");
  }

  // ------------------------------------------------------------------ helpers

  private void createAccount(String id, String iban, String balance) {
    accountRepository.save(new Account(id, new Iban(iban), new Money(new BigDecimal(balance), "EUR")));
  }

  private static String masked(String iban) {
    return iban.substring(0, 4) + "*".repeat(iban.length() - 8) + iban.substring(iban.length() - 4);
  }

  private MvcResult postTransfer(String destIban, String amount) throws Exception {
    String body = "{\"sourceIban\":\"" + source + "\",\"destIban\":\"" + destIban + "\","
      + "\"amount\":" + amount + ",\"currency\":\"EUR\"}";
    return mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
  }

  private MvcResult getTransfer(String id) throws Exception {
    return mockMvc.perform(get("/transfers/{id}", id)).andReturn();
  }

  private static String text(MvcResult result, String path) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), path);
  }

  private static BigDecimal amountOf(MvcResult result) throws Exception {
    Object amount = JsonPath.read(result.getResponse().getContentAsString(), "$.amount");
    return new BigDecimal(amount.toString());
  }

  private static List<String> reasons(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.reasons");
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  // ------------------------------------------------------------------ external transfers

  @Test
  void externalTransferIsReadAsSentThenCompleted() throws Exception {
    MvcResult posted = postTransfer(EXTERNAL_IBAN, AMOUNT);
    assertThat(posted.getResponse().getStatus()).isEqualTo(202);
    String transferId = text(posted, "$.transferId");

    MvcResult sent = getTransfer(transferId);

    assertThat(sent.getResponse().getStatus()).isEqualTo(200);
    assertThat(text(sent, "$.transferId")).isEqualTo(transferId);
    assertThat(text(sent, "$.transferStatus")).isEqualTo("SENT");
    assertThat(text(sent, "$.sourceIbanMasked")).isEqualTo(masked(source));
    assertThat(text(sent, "$.destIbanMasked")).isEqualTo(masked(EXTERNAL_IBAN));
    assertThat(amountOf(sent)).isEqualByComparingTo(AMOUNT);
    assertThat(text(sent, "$.currency")).isEqualTo("EUR");
    assertThat(reasons(sent)).isEmpty();

    assertThat(processClearingOutcomes.processOutcomes()).isEqualTo(1);

    MvcResult completed = getTransfer(transferId);
    assertThat(completed.getResponse().getStatus()).isEqualTo(200);
    assertThat(text(completed, "$.transferStatus")).isEqualTo("COMPLETED");
    assertThat(amountOf(completed)).isEqualByComparingTo(AMOUNT);
    assertThat(reasons(completed)).isEmpty();
  }

  @Test
  void externalRejectionIsReadWithTheReason() throws Exception {
    simulator.setMode(SimulatedClearingModeEnum.REJECT);
    MvcResult posted = postTransfer(EXTERNAL_IBAN, AMOUNT);
    assertThat(posted.getResponse().getStatus()).isEqualTo(202);
    String transferId = text(posted, "$.transferId");

    assertThat(text(getTransfer(transferId), "$.transferStatus")).isEqualTo("SENT");
    assertThat(processClearingOutcomes.processOutcomes()).isEqualTo(1);

    MvcResult rejected = getTransfer(transferId);

    assertThat(rejected.getResponse().getStatus()).isEqualTo(200);
    assertThat(text(rejected, "$.transferStatus")).isEqualTo("REJECTED");
    assertThat(reasons(rejected)).containsExactly(SimulatedClearingHouseOutPortImpl.REJECT_REASON);
  }

  // ------------------------------------------------------------------ internal transfers

  @Test
  void internalTransferIsReadAsCompleted() throws Exception {
    String dest = IBAN_PREFIX + String.format("R%05d", SEQUENCE.get());
    createAccount("e2e-query-dest-" + SEQUENCE.get(), dest, "0");
    MvcResult posted = postTransfer(dest, AMOUNT);
    assertThat(posted.getResponse().getStatus()).isEqualTo(200);
    String transferId = text(posted, "$.transferId");

    MvcResult result = getTransfer(transferId);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(text(result, "$.transferId")).isEqualTo(transferId);
    assertThat(text(result, "$.transferStatus")).isEqualTo("COMPLETED");
    assertThat(text(result, "$.sourceIbanMasked")).isEqualTo(masked(source));
    assertThat(text(result, "$.destIbanMasked")).isEqualTo(masked(dest));
    assertThat(amountOf(result)).isEqualByComparingTo(AMOUNT);
    assertThat(text(result, "$.currency")).isEqualTo("EUR");
    assertThat(reasons(result)).isEmpty();
  }

  // ------------------------------------------------------------------ seeded data

  @Test
  void seededTransfersCanBeRead() throws Exception {
    MvcResult internal = getTransfer("tr1");

    assertThat(internal.getResponse().getStatus()).isEqualTo(200);
    assertThat(text(internal, "$.transferId")).isEqualTo("tr1");
    assertThat(text(internal, "$.transferStatus")).isEqualTo("COMPLETED");
    assertThat(text(internal, "$.sourceIbanMasked")).isEqualTo("IT60" + "*".repeat(19) + "0001");
    assertThat(text(internal, "$.destIbanMasked")).isEqualTo("IT60" + "*".repeat(19) + "0002");
    assertThat(amountOf(internal)).isEqualByComparingTo("100.00");
    assertThat(text(internal, "$.currency")).isEqualTo("EUR");

    MvcResult external = getTransfer("demo-ext-009");

    assertThat(external.getResponse().getStatus()).isEqualTo(200);
    assertThat(text(external, "$.transferId")).isEqualTo("demo-ext-009");
    // a reconciliation run by another test of the same context could have moved it from SENT to REJECTED
    assertThat(text(external, "$.transferStatus")).isIn("SENT", "REJECTED");
    assertThat(text(external, "$.sourceIbanMasked")).isEqualTo("IT60" + "*".repeat(19) + "0149");
    assertThat(text(external, "$.destIbanMasked")).isEqualTo("IT60" + "*".repeat(19) + "0009");
    assertThat(amountOf(external)).isEqualByComparingTo("457.57");
    assertThat(text(external, "$.currency")).isEqualTo("EUR");
  }

  // ------------------------------------------------------------------ errors and confidentiality

  @Test
  void unknownIdAnswers404() throws Exception {
    MvcResult result = getTransfer("no-such-transfer");

    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(text(result, "$.detail")).isEqualTo(PROBLEM_DETAIL);
    assertThat(body(result)).doesNotContain("no-such-transfer").doesNotContain("IT60");
  }

  @Test
  void aMalformedIdIsSimplyNotFoundAndNeverA400() throws Exception {
    MvcResult result = getTransfer("not an id!");

    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(text(result, "$.detail")).isEqualTo(PROBLEM_DETAIL);
  }

  @Test
  void responsesNeverContainAFullIban() throws Exception {
    List<String> bodies = new ArrayList<>();
    String dest = IBAN_PREFIX + String.format("R%05d", SEQUENCE.get());
    createAccount("e2e-query-dest-" + SEQUENCE.get(), dest, "0");

    MvcResult internalPost = postTransfer(dest, AMOUNT);
    bodies.add(body(getTransfer(text(internalPost, "$.transferId"))));

    MvcResult externalPost = postTransfer(EXTERNAL_IBAN, AMOUNT);
    String externalId = text(externalPost, "$.transferId");
    bodies.add(body(getTransfer(externalId)));
    processClearingOutcomes.processOutcomes();
    bodies.add(body(getTransfer(externalId)));

    simulator.setMode(SimulatedClearingModeEnum.REJECT);
    MvcResult rejectedPost = postTransfer(EXTERNAL_IBAN, AMOUNT);
    String rejectedId = text(rejectedPost, "$.transferId");
    processClearingOutcomes.processOutcomes();
    bodies.add(body(getTransfer(rejectedId)));

    bodies.add(body(getTransfer("tr1")));
    bodies.add(body(getTransfer("demo-ext-009")));
    bodies.add(body(getTransfer("no-such-transfer")));

    assertThat(bodies).hasSize(7).allSatisfy(responseBody -> assertThat(responseBody)
      .doesNotContain(source)
      .doesNotContain(dest)
      .doesNotContain(EXTERNAL_IBAN)
      .doesNotContain(SEEDED_SOURCE_IBAN)
      .doesNotContain("IT60X0542811101000000000002")
      .doesNotContain("IT60X0542811101000000000149")
      .doesNotContain("IT60X0306911101000000000009"));
  }

  @Test
  void logsNeverContainIbansNorAmounts(CapturedOutput output) throws Exception {
    // unusual amount, so that unrelated log lines cannot match by chance
    String distinctiveAmount = "217.31";
    MvcResult sentPost = postTransfer(EXTERNAL_IBAN, distinctiveAmount);
    String sentId = text(sentPost, "$.transferId");
    getTransfer(sentId);
    processClearingOutcomes.processOutcomes();
    getTransfer(sentId);

    simulator.setMode(SimulatedClearingModeEnum.REJECT);
    MvcResult rejectedPost = postTransfer(EXTERNAL_IBAN, distinctiveAmount);
    String rejectedId = text(rejectedPost, "$.transferId");
    processClearingOutcomes.processOutcomes();
    getTransfer(rejectedId);

    getTransfer("tr1");
    getTransfer("demo-ext-009");
    getTransfer("no-such-transfer");

    assertThat(output.getAll())
      .doesNotContain(source)
      .doesNotContain(EXTERNAL_IBAN)
      .doesNotContain(SEEDED_SOURCE_IBAN)
      .doesNotContain(distinctiveAmount);
  }
}
