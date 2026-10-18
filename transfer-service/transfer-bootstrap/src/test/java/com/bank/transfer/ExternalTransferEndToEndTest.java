package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * POST /transfers from the HTTP layer down to the real H2 database: the router sends the transfers to another bank
 * to the Saga (simulated clearing house, processOutcomes() called by hand) and the others to the internal flow.
 * The fraud check is a hand written fake, so there is no network. Dedicated in-memory H2 and Spring context.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:externale2e;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class ExternalTransferEndToEndTest {

  private static final FraudCheckResultDto ALLOWED = new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
  private static final AtomicReference<FraudCheckResultDto> FRAUD_DECISION = new AtomicReference<>(ALLOWED);
  private static final AtomicInteger FRAUD_CALLS = new AtomicInteger();

  @TestConfiguration
  static class ConfigurableFraudCheck {

    @Bean
    @Primary
    FraudCheckOutPort configurableFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) -> {
        FRAUD_CALLS.incrementAndGet();
        return FRAUD_DECISION.get();
      };
    }
  }

  /** ABI 05428: an account of this bank. */
  private static final String IBAN_PREFIX = "IT60X0542811101000000";
  /** ABI 03069: another bank. */
  private static final String EXTERNAL_IBAN = "IT60X0306909606100000012345";
  private static final String AMOUNT = "123.45";
  private static final String UNAVAILABLE_REASON = "Clearing house unavailable";
  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ProcessClearingOutcomesInPort processClearingOutcomes;

  @Autowired
  private SimulatedClearingHouseOutPortImpl simulator;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private String source;

  @BeforeEach
  void setUp() {
    // the simulator is a singleton shared by the tests: back to ACCEPT and with nothing left in its queue
    simulator.setMode(SimulatedClearingModeEnum.ACCEPT);
    simulator.pollOutcomes();
    FRAUD_DECISION.set(ALLOWED);
    FRAUD_CALLS.set(0);

    int number = SEQUENCE.incrementAndGet();
    source = IBAN_PREFIX + String.format("S%05d", number);
    createAccount("e2e-ext-" + number, source, "1000");
  }

  // ------------------------------------------------------------------ helpers

  private void createAccount(String id, String iban, String balance) {
    accountRepository.save(new Account(id, new Iban(iban), new Money(new BigDecimal(balance), "EUR")));
  }

  private BigDecimal balanceOf(String iban) {
    return accountRepository.findByIban(new Iban(iban)).orElseThrow().money().amount();
  }

  private MvcResult postTransfer(String destIban, String amount) throws Exception {
    String body = "{\"sourceIban\":\"" + source + "\",\"destIban\":\"" + destIban + "\","
      + "\"amount\":" + amount + ",\"currency\":\"EUR\"}";
    return mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
  }

  private static String field(MvcResult result, String path) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), path);
  }

  private static List<String> reasons(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.reasons");
  }

  private int count(String sql, Object... args) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
    assertThat(count).isNotNull();
    return count;
  }

  private String externalStatus(String transferId) {
    return jdbcTemplate.queryForObject("select status from external_transfer where id = ?", String.class,
      transferId);
  }

  private String externalReason(String transferId) {
    return jdbcTemplate.queryForObject("select reason from external_transfer where id = ?", String.class,
      transferId);
  }

  private int externalRowsOfTheSource() {
    return count("select count(*) from external_transfer where source_iban = ?", source);
  }

  private int transferRowsOfTheSource() {
    return count("select count(*) from transfer where source_iban = ?", source);
  }

  private int outboxRows(String transferId) {
    return count("select count(*) from outbox where transfer_id = ?", transferId);
  }

  private int outboxRowsOfTheSource() {
    return count("select count(*) from outbox where payload like ?", "%\"sourceIban\":\"" + source + "\"%");
  }

  private String outboxPayload(String transferId) {
    return jdbcTemplate.queryForObject("select payload from outbox where transfer_id = ?", String.class,
      transferId);
  }

  // ------------------------------------------------------------------ external transfers

  @Test
  void acceptedExternalTransferAnswers202SentAndIsCompletedByTheOutcome() throws Exception {
    MvcResult result = postTransfer(EXTERNAL_IBAN, AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(202);
    String transferId = field(result, "$.transferId");
    assertThat(field(result, "$.transferStatus")).isEqualTo("SENT");
    assertThat(reasons(result)).isEmpty();
    assertThat(balanceOf(source)).isEqualByComparingTo("876.55");
    assertThat(externalStatus(transferId)).isEqualTo("SENT");
    assertThat(transferRowsOfTheSource()).isZero();
    assertThat(outboxRows(transferId)).isZero();

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(externalStatus(transferId)).isEqualTo("COMPLETED");
    assertThat(outboxRows(transferId)).isEqualTo(1);
    assertThat(outboxPayload(transferId)).contains("\"status\":\"COMPLETED\"")
      .contains("\"destinationIban\":\"" + EXTERNAL_IBAN + "\"");
    assertThat(balanceOf(source)).isEqualByComparingTo("876.55");
  }

  @Test
  void rejectedExternalTransferIsRefunded() throws Exception {
    simulator.setMode(SimulatedClearingModeEnum.REJECT);

    MvcResult result = postTransfer(EXTERNAL_IBAN, AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(202);
    String transferId = field(result, "$.transferId");
    assertThat(field(result, "$.transferStatus")).isEqualTo("SENT");
    assertThat(balanceOf(source)).isEqualByComparingTo("876.55");

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(externalStatus(transferId)).isEqualTo("REJECTED");
    assertThat(externalReason(transferId)).isEqualTo(SimulatedClearingHouseOutPortImpl.REJECT_REASON);
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
    assertThat(outboxRows(transferId)).isEqualTo(1);
    assertThat(outboxPayload(transferId)).contains("\"status\":\"REJECTED\"")
      .contains(SimulatedClearingHouseOutPortImpl.REJECT_REASON);
  }

  @Test
  void unavailableClearingAnswers200RejectedAndRefundsAtOnce() throws Exception {
    simulator.setMode(SimulatedClearingModeEnum.UNAVAILABLE);

    MvcResult result = postTransfer(EXTERNAL_IBAN, AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    String transferId = field(result, "$.transferId");
    assertThat(field(result, "$.transferStatus")).isEqualTo("REJECTED");
    assertThat(reasons(result)).containsExactly(UNAVAILABLE_REASON);
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
    assertThat(outboxRows(transferId)).isEqualTo(1);
    assertThat(outboxPayload(transferId)).contains("\"status\":\"REJECTED\"");
  }

  @Test
  void timeoutThenAcceptAnswers202UnknownThenCompletes() throws Exception {
    simulator.setMode(SimulatedClearingModeEnum.TIMEOUT_THEN_ACCEPT);

    MvcResult result = postTransfer(EXTERNAL_IBAN, AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(202);
    String transferId = field(result, "$.transferId");
    assertThat(field(result, "$.transferStatus")).isEqualTo("UNKNOWN");
    assertThat(balanceOf(source)).isEqualByComparingTo("876.55");
    assertThat(outboxRows(transferId)).isZero();

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(externalStatus(transferId)).isEqualTo("COMPLETED");
    assertThat(outboxRows(transferId)).isEqualTo(1);
    assertThat(balanceOf(source)).isEqualByComparingTo("876.55");
  }

  @Test
  void timeoutThenRejectAnswers202UnknownThenRefunds() throws Exception {
    simulator.setMode(SimulatedClearingModeEnum.TIMEOUT_THEN_REJECT);

    MvcResult result = postTransfer(EXTERNAL_IBAN, AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(202);
    String transferId = field(result, "$.transferId");
    assertThat(field(result, "$.transferStatus")).isEqualTo("UNKNOWN");
    assertThat(balanceOf(source)).isEqualByComparingTo("876.55");

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(externalStatus(transferId)).isEqualTo("REJECTED");
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
    assertThat(outboxRows(transferId)).isEqualTo(1);
    assertThat(outboxPayload(transferId)).contains("\"status\":\"REJECTED\"");
  }

  @Test
  void fraudRejectionOfAnExternalTransferAnswers200AndMovesNoMoney() throws Exception {
    FRAUD_DECISION.set(new FraudCheckResultDto(FraudStatusEnum.REJECTED, List.of("A", "B")));

    MvcResult result = postTransfer(EXTERNAL_IBAN, AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    String transferId = field(result, "$.transferId");
    assertThat(field(result, "$.transferStatus")).isEqualTo("REJECTED");
    assertThat(reasons(result)).containsExactly("A", "B");
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
    assertThat(externalStatus(transferId)).isEqualTo("REJECTED");
    assertThat(externalReason(transferId)).isEqualTo("A; B");
    assertThat(outboxRows(transferId)).isEqualTo(1);
    assertThat(processClearingOutcomes.processOutcomes()).isZero();
  }

  @Test
  void insufficientFundsOnAnExternalTransferAnswers422AndSavesNothing() throws Exception {
    MvcResult result = postTransfer(EXTERNAL_IBAN, "2000");

    assertThat(result.getResponse().getStatus()).isEqualTo(422);
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
    assertThat(externalRowsOfTheSource()).isZero();
    assertThat(processClearingOutcomes.processOutcomes()).isZero();
  }

  // ------------------------------------------------------------------ internal flow and errors

  @Test
  void missingInternalDestinationAnswers404AsBefore() throws Exception {
    MvcResult result = postTransfer(IBAN_PREFIX + "N00001", AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    assertThat(result.getResponse().getContentAsString()).contains("Destination Account not found");
    assertThat(FRAUD_CALLS.get()).isZero();
    assertThat(transferRowsOfTheSource()).isZero();
    assertThat(externalRowsOfTheSource()).isZero();
    assertThat(outboxRowsOfTheSource()).isZero();
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
  }

  @Test
  void internalTransferStillAnswers200Completed() throws Exception {
    String dest = IBAN_PREFIX + String.format("D%05d", SEQUENCE.get());
    createAccount("e2e-ext-dest-" + SEQUENCE.get(), dest, "0");

    MvcResult result = postTransfer(dest, AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(field(result, "$.transferStatus")).isEqualTo("COMPLETED");
    assertThat(transferRowsOfTheSource()).isEqualTo(1);
    assertThat(externalRowsOfTheSource()).isZero();
    assertThat(balanceOf(source)).isEqualByComparingTo("876.55");
    assertThat(balanceOf(dest)).isEqualByComparingTo("123.45");
  }

  @Test
  void destinationOfTheWrongLengthAnswers400() throws Exception {
    MvcResult result = postTransfer(EXTERNAL_IBAN.substring(1), AMOUNT);

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(transferRowsOfTheSource()).isZero();
    assertThat(externalRowsOfTheSource()).isZero();
    assertThat(outboxRowsOfTheSource()).isZero();
    assertThat(FRAUD_CALLS.get()).isZero();
    assertThat(balanceOf(source)).isEqualByComparingTo("1000");
  }

  // ------------------------------------------------------------------ logs

  @Test
  void logsNeverContainIbansNorAmounts(CapturedOutput output) throws Exception {
    // unusual amount, so that unrelated log lines cannot match by chance;
    // 4 transfers of 217.31 stay within the initial balance of 1000
    String distinctiveAmount = "217.31";
    postTransfer(EXTERNAL_IBAN, distinctiveAmount);
    processClearingOutcomes.processOutcomes();

    simulator.setMode(SimulatedClearingModeEnum.REJECT);
    postTransfer(EXTERNAL_IBAN, distinctiveAmount);
    processClearingOutcomes.processOutcomes();

    simulator.setMode(SimulatedClearingModeEnum.UNAVAILABLE);
    postTransfer(EXTERNAL_IBAN, distinctiveAmount);

    simulator.setMode(SimulatedClearingModeEnum.TIMEOUT_THEN_ACCEPT);
    postTransfer(EXTERNAL_IBAN, distinctiveAmount);
    processClearingOutcomes.processOutcomes();

    assertThat(output.getAll())
        .doesNotContain(source)
        .doesNotContain(EXTERNAL_IBAN)
        .doesNotContain(distinctiveAmount);
  }
}
