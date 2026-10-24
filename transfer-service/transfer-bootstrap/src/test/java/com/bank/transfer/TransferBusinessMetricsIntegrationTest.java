package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.exception.NotificationSendException;
import com.bank.transfer.application.port.in.ProcessClearingOutcomesInPort;
import com.bank.transfer.application.port.in.PublishPendingNotificationsInPort;
import com.bank.transfer.application.port.in.ReconcileExternalTransfersInPort;
import com.bank.transfer.application.port.in.RefreshOutboxMetricsInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.ExternalTransferRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.NotificationSenderOutPort;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingHouseOutPortImpl;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingModeEnum;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
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
 * Metriche di business sulla configurazione reale (piano transfer-14, M1-M11): servizi veri, adapter JPA veri, stanza
 * simulata in ACCEPT, H2 in memoria dedicato. L'antifrode e' un fake che rifiuta sopra 5000, il sender delle
 * notifiche e' un fake che registra. Le asserzioni sono a delta sul MeterRegistry (il contesto e' condiviso fra i
 * test e il seed demo c'e'); i conti sono nostri (non demo-*), gli importi riconoscibili.
 * Lo scheduler del gauge resta acceso per default: M8 chiama la porta in a mano e confronta il valore subito dopo.
 * M10: @AutoConfigureMetrics riattiva l'export (nei test e' spento) e quindi /actuator/prometheus.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:metrics;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@AutoConfigureMetrics
@ExtendWith(OutputCaptureExtension.class)
class TransferBusinessMetricsIntegrationTest {

  private static final BigDecimal FRAUD_THRESHOLD = new BigDecimal("5000");
  private static final Set<String> FAILING_SEND_IDS = new CopyOnWriteArraySet<>();
  private static final List<String> SENT_IDS = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class Collaborators {

    /** Rejects above 5000, accepts otherwise; no network. */
    @Bean
    @Primary
    FraudCheckOutPort thresholdFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) -> amount.compareTo(FRAUD_THRESHOLD) > 0
        ? new FraudCheckResultDto(FraudStatusEnum.REJECTED, List.of("Amount must not exceed 5000 EUR"))
        : new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
    }

    /** Records the successful sends; fails for the ids in FAILING_SEND_IDS. */
    @Bean
    @Primary
    NotificationSenderOutPort recordingSender() {
      return (transferId, payload) -> {
        if (FAILING_SEND_IDS.contains(transferId)) {
          throw new NotificationSendException(transferId);
        }
        SENT_IDS.add(transferId);
      };
    }
  }

  /** ABI 05428: an account of this bank. Letters in the account part: never an IBAN of the demo data. */
  private static final String IBAN_PREFIX = "IT60X0542811101000000";
  /** ABI 03069: another bank. */
  private static final String EXTERNAL_IBAN = "IT60X0306909606100000012345";
  private static final String COMPLETED_AMOUNT = "1234.57";
  private static final String REJECTED_AMOUNT = "5555.55";
  private static final String EXTERNAL_AMOUNT = "2345.68";
  private static final String EXTERNAL_REJECTED_AMOUNT = "6666.66";
  private static final List<String> RECOGNIZABLE_AMOUNTS =
    List.of(COMPLETED_AMOUNT, REJECTED_AMOUNT, EXTERNAL_AMOUNT, EXTERNAL_REJECTED_AMOUNT, "9876.54");
  private static final Pattern ENUM_NAME = Pattern.compile("^[A-Z_]+$");
  private static final Pattern IBAN_LIKE = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");
  private static final AtomicInteger SEQUENCE = new AtomicInteger();
  private static final int MAX = 100;

  private static final String REQUESTS = "bank.transfer.requests";
  private static final String CONCLUDED = "bank.transfer.concluded";
  private static final String REFUNDS = "bank.transfer.refunds";
  private static final String RECONCILIATIONS = "bank.transfer.reconciliations";
  private static final String PUBLISHED = "bank.transfer.outbox.published";
  private static final String PENDING = "bank.transfer.outbox.pending";

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private MeterRegistry registry;

  @Autowired
  private ProcessClearingOutcomesInPort processClearingOutcomes;

  @Autowired
  private ReconcileExternalTransfersInPort reconcileExternalTransfers;

  @Autowired
  private PublishPendingNotificationsInPort publishPendingNotifications;

  @Autowired
  private RefreshOutboxMetricsInPort refreshOutboxMetrics;

  @Autowired
  private SimulatedClearingHouseOutPortImpl simulator;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private ExternalTransferRepositoryOutPort externalTransferRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private String source;
  private String internalDest;
  private final List<String> transferIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    // the simulator is a singleton shared by the tests: back to ACCEPT and with nothing left in its queue
    simulator.setMode(SimulatedClearingModeEnum.ACCEPT);
    simulator.pollOutcomes();
    FAILING_SEND_IDS.clear();

    int number = SEQUENCE.incrementAndGet();
    source = IBAN_PREFIX + String.format("M%05d", number);
    internalDest = IBAN_PREFIX + String.format("N%05d", number);
    createAccount("metrics-src-" + number, source, "100000");
    createAccount("metrics-dst-" + number, internalDest, "0");
  }

  // ------------------------------------------------------------------ helpers

  private void createAccount(String id, String iban, String balance) {
    accountRepository.save(new Account(id, new Iban(iban), new Money(new BigDecimal(balance), "EUR")));
  }

  private MvcResult postTransfer(String destIban, String amount, int expectedStatus) throws Exception {
    return postTransferFrom(source, destIban, amount, expectedStatus);
  }

  private MvcResult postTransferFrom(String sourceIban, String destIban, String amount, int expectedStatus)
    throws Exception {
    String body = "{\"sourceIban\":\"" + sourceIban + "\",\"destIban\":\"" + destIban + "\","
      + "\"amount\":" + amount + ",\"currency\":\"EUR\"}";
    MvcResult result = mockMvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
      .andExpect(status().is(expectedStatus)).andReturn();
    if (expectedStatus == 200 || expectedStatus == 202) {
      transferIds.add(JsonPath.read(result.getResponse().getContentAsString(), "$.transferId"));
    }
    return result;
  }

  private static String statusOf(MvcResult result) throws Exception {
    return JsonPath.read(result.getResponse().getContentAsString(), "$.transferStatus");
  }

  /** Sum of the counters with that name and those tags (key, value, key, value...); 0 if none exists. */
  private double count(String name, String... tags) {
    return registry.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
  }

  private Map<String, Double> counterSnapshot() {
    Map<String, Double> snapshot = new TreeMap<>();
    for (Meter meter : registry.getMeters()) {
      if (meter.getId().getName().startsWith("bank.transfer.") && meter instanceof Counter counter) {
        snapshot.put(meter.getId().toString(), counter.count());
      }
    }
    return snapshot;
  }

  private double gauge() {
    return registry.get(PENDING).gauge().value();
  }

  private int outboxRows() {
    Integer rows = jdbcTemplate.queryForObject("select count(*) from outbox", Integer.class);
    assertThat(rows).isNotNull();
    return rows;
  }

  private String externalStatus(String transferId) {
    return jdbcTemplate.queryForObject("select status from external_transfer where id = ?", String.class,
      transferId);
  }

  /** A transfer debited and saved as SENT with the ports, but never submitted to the clearing house. */
  private String sentButNeverSubmitted(String amount) {
    Money money = new Money(new BigDecimal(amount), "EUR");
    Account account = accountRepository.findByIban(new Iban(source)).orElseThrow();
    accountRepository.save(account.charge(money));
    Transfer sent = Transfer.create(new Iban(source), new Iban(EXTERNAL_IBAN), money).markSent();
    externalTransferRepository.save(sent, null);
    transferIds.add(sent.id());
    return sent.id();
  }

  private static Instant everythingIsStale() {
    return Instant.now().plusSeconds(1);
  }

  /** Brings every SENT and UNKNOWN row of the context to a concluded state, so the next round starts clean. */
  private void drainInProgressTransfers() {
    processClearingOutcomes.processOutcomes();
    reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);
  }

  /** Internal completed, internal rejected, external sent, external completed by the poll, external rejected. */
  private void generateTraffic() throws Exception {
    postTransfer(internalDest, COMPLETED_AMOUNT, 200);
    postTransfer(internalDest, REJECTED_AMOUNT, 200);
    postTransfer(EXTERNAL_IBAN, EXTERNAL_AMOUNT, 202);
    processClearingOutcomes.processOutcomes();
    postTransfer(EXTERNAL_IBAN, EXTERNAL_REJECTED_AMOUNT, 200);
  }

  private void assertConcludedUnchanged(Map<String, Double> before) {
    Map<String, Double> after = counterSnapshot();
    for (Map.Entry<String, Double> entry : before.entrySet()) {
      if (entry.getKey().startsWith(CONCLUDED)) {
        assertThat(after.get(entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
      }
    }
  }

  // ------------------------------------------------------------------ M1, M2: interni

  @Test
  void m1_allowedInternalTransfer_requestsAndConcludedCompletedGrowByOne() throws Exception {
    double requests = count(REQUESTS, "type", "INTERNAL", "status", "COMPLETED");
    double concluded = count(CONCLUDED, "type", "INTERNAL", "status", "COMPLETED");
    double rejected = count(REQUESTS, "type", "INTERNAL", "status", "REJECTED");

    MvcResult result = postTransfer(internalDest, COMPLETED_AMOUNT, 200);

    assertThat(statusOf(result)).isEqualTo("COMPLETED");
    assertThat(count(REQUESTS, "type", "INTERNAL", "status", "COMPLETED")).isEqualTo(requests + 1);
    assertThat(count(CONCLUDED, "type", "INTERNAL", "status", "COMPLETED")).isEqualTo(concluded + 1);
    assertThat(count(REQUESTS, "type", "INTERNAL", "status", "REJECTED")).isEqualTo(rejected);
  }

  @Test
  void m2_internalTransferOverTheThreshold_requestsAndConcludedRejectedGrowByOneAndNoRefund() throws Exception {
    double requests = count(REQUESTS, "type", "INTERNAL", "status", "REJECTED");
    double concluded = count(CONCLUDED, "type", "INTERNAL", "status", "REJECTED");
    double completed = count(CONCLUDED, "type", "INTERNAL", "status", "COMPLETED");
    double refunds = count(REFUNDS);

    MvcResult result = postTransfer(internalDest, REJECTED_AMOUNT, 200);

    assertThat(statusOf(result)).isEqualTo("REJECTED");
    assertThat(count(REQUESTS, "type", "INTERNAL", "status", "REJECTED")).isEqualTo(requests + 1);
    assertThat(count(CONCLUDED, "type", "INTERNAL", "status", "REJECTED")).isEqualTo(concluded + 1);
    assertThat(count(CONCLUDED, "type", "INTERNAL", "status", "COMPLETED")).isEqualTo(completed);
    assertThat(count(REFUNDS)).isEqualTo(refunds);
  }

  @Test
  void m2_amountEqualToTheThreshold_isCountedAsCompleted() throws Exception {
    double completed = count(REQUESTS, "type", "INTERNAL", "status", "COMPLETED");
    double rejected = count(REQUESTS, "type", "INTERNAL", "status", "REJECTED");

    MvcResult result = postTransfer(internalDest, "5000.00", 200);

    assertThat(statusOf(result)).isEqualTo("COMPLETED");
    assertThat(count(REQUESTS, "type", "INTERNAL", "status", "COMPLETED")).isEqualTo(completed + 1);
    assertThat(count(REQUESTS, "type", "INTERNAL", "status", "REJECTED")).isEqualTo(rejected);
  }

  // ------------------------------------------------------------------ M3: errori, nessun conteggio

  @Test
  void m3_internalTransferToAMissingAccount_404AndNoBusinessCounterChanges() throws Exception {
    Map<String, Double> before = counterSnapshot();

    postTransfer(IBAN_PREFIX + "X99999", COMPLETED_AMOUNT, 404);

    assertThat(counterSnapshot()).isEqualTo(before);
  }

  @Test
  void m3_missingSourceAccount_404AndNoBusinessCounterChanges() throws Exception {
    Map<String, Double> before = counterSnapshot();

    postTransferFrom(IBAN_PREFIX + "X99998", internalDest, COMPLETED_AMOUNT, 404);
    postTransferFrom(IBAN_PREFIX + "X99998", EXTERNAL_IBAN, COMPLETED_AMOUNT, 404);

    assertThat(counterSnapshot()).isEqualTo(before);
  }

  @Test
  void m3_insufficientFunds_422AndNoBusinessCounterChanges() throws Exception {
    String poor = IBAN_PREFIX + String.format("P%05d", SEQUENCE.get());
    createAccount("metrics-poor-" + SEQUENCE.get(), poor, "10");
    Map<String, Double> before = counterSnapshot();

    postTransferFrom(poor, internalDest, COMPLETED_AMOUNT, 422);
    postTransferFrom(poor, EXTERNAL_IBAN, COMPLETED_AMOUNT, 422);

    assertThat(counterSnapshot()).isEqualTo(before);
  }

  @Test
  void m3_invalidRequests_400AndNoBusinessCounterChanges() throws Exception {
    Map<String, Double> before = counterSnapshot();

    postTransfer(source, COMPLETED_AMOUNT, 400);
    postTransfer(EXTERNAL_IBAN.substring(1), COMPLETED_AMOUNT, 400);

    assertThat(counterSnapshot()).isEqualTo(before);
  }

  // ------------------------------------------------------------------ M4, M5: esterni

  @Test
  void m4_allowedExternalTransfer_requestsSentGrowsByOneAndConcludedIsUnchanged() throws Exception {
    double sent = count(REQUESTS, "type", "EXTERNAL", "status", "SENT");
    Map<String, Double> before = counterSnapshot();

    MvcResult result = postTransfer(EXTERNAL_IBAN, EXTERNAL_AMOUNT, 202);

    assertThat(statusOf(result)).isEqualTo("SENT");
    assertThat(count(REQUESTS, "type", "EXTERNAL", "status", "SENT")).isEqualTo(sent + 1);
    assertConcludedUnchanged(before);
  }

  @Test
  void m4_fraudRejectedExternalTransfer_requestsAndConcludedRejectedGrowByOneAndNoRefund() throws Exception {
    double requests = count(REQUESTS, "type", "EXTERNAL", "status", "REJECTED");
    double concluded = count(CONCLUDED, "type", "EXTERNAL", "status", "REJECTED");
    double refunds = count(REFUNDS);

    MvcResult result = postTransfer(EXTERNAL_IBAN, EXTERNAL_REJECTED_AMOUNT, 200);

    assertThat(statusOf(result)).isEqualTo("REJECTED");
    assertThat(count(REQUESTS, "type", "EXTERNAL", "status", "REJECTED")).isEqualTo(requests + 1);
    assertThat(count(CONCLUDED, "type", "EXTERNAL", "status", "REJECTED")).isEqualTo(concluded + 1);
    assertThat(count(REFUNDS)).isEqualTo(refunds);
  }

  @Test
  void m4_unavailableClearingHouse_requestsConcludedRejectedAndRefundUnavailableGrowByOne() throws Exception {
    simulator.setMode(SimulatedClearingModeEnum.UNAVAILABLE);
    double requests = count(REQUESTS, "type", "EXTERNAL", "status", "REJECTED");
    double concluded = count(CONCLUDED, "type", "EXTERNAL", "status", "REJECTED");
    double refund = count(REFUNDS, "cause", "CLEARING_UNAVAILABLE");

    MvcResult result = postTransfer(EXTERNAL_IBAN, EXTERNAL_AMOUNT, 200);

    assertThat(statusOf(result)).isEqualTo("REJECTED");
    assertThat(count(REQUESTS, "type", "EXTERNAL", "status", "REJECTED")).isEqualTo(requests + 1);
    assertThat(count(CONCLUDED, "type", "EXTERNAL", "status", "REJECTED")).isEqualTo(concluded + 1);
    assertThat(count(REFUNDS, "cause", "CLEARING_UNAVAILABLE")).isEqualTo(refund + 1);
  }

  @Test
  void m4_clearingHouseTimeout_requestsUnknownGrowsByOneAndNothingIsConcluded() throws Exception {
    simulator.setMode(SimulatedClearingModeEnum.TIMEOUT_THEN_ACCEPT);
    double unknown = count(REQUESTS, "type", "EXTERNAL", "status", "UNKNOWN");
    Map<String, Double> before = counterSnapshot();

    MvcResult result = postTransfer(EXTERNAL_IBAN, EXTERNAL_AMOUNT, 202);

    assertThat(statusOf(result)).isEqualTo("UNKNOWN");
    assertThat(count(REQUESTS, "type", "EXTERNAL", "status", "UNKNOWN")).isEqualTo(unknown + 1);
    assertConcludedUnchanged(before);
  }

  @Test
  void m5_afterTheSentTransfer_processOutcomesCountsTheConclusionAsCompletedAndNoNewRequest() throws Exception {
    postTransfer(EXTERNAL_IBAN, EXTERNAL_AMOUNT, 202);
    double concluded = count(CONCLUDED, "type", "EXTERNAL", "status", "COMPLETED");
    double requests = count(REQUESTS);

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(count(CONCLUDED, "type", "EXTERNAL", "status", "COMPLETED")).isEqualTo(concluded + 1);
    assertThat(count(REQUESTS)).isEqualTo(requests);
  }

  @Test
  void m5_lateRejectionOfASentTransfer_countsConclusionAndClearingRejectedRefund() throws Exception {
    simulator.setMode(SimulatedClearingModeEnum.REJECT);
    postTransfer(EXTERNAL_IBAN, EXTERNAL_AMOUNT, 202);
    double concluded = count(CONCLUDED, "type", "EXTERNAL", "status", "REJECTED");
    double refund = count(REFUNDS, "cause", "CLEARING_REJECTED");

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(count(CONCLUDED, "type", "EXTERNAL", "status", "REJECTED")).isEqualTo(concluded + 1);
    assertThat(count(REFUNDS, "cause", "CLEARING_REJECTED")).isEqualTo(refund + 1);
  }

  @Test
  void m5_secondPollOfTheSameOutcome_countsNothingMore() throws Exception {
    postTransfer(EXTERNAL_IBAN, EXTERNAL_AMOUNT, 202);
    processClearingOutcomes.processOutcomes();
    Map<String, Double> before = counterSnapshot();

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isZero();
    assertThat(counterSnapshot()).isEqualTo(before);
  }

  // ------------------------------------------------------------------ M6: riconciliazione

  @Test
  void m6_sentTransferUnknownToTheClearingHouse_reconciliationNotFoundRefundAndConclusionRejected() {
    drainInProgressTransfers();
    String transferId = sentButNeverSubmitted(EXTERNAL_AMOUNT);
    double notFound = count(RECONCILIATIONS, "outcome", "NOT_FOUND");
    double refund = count(REFUNDS, "cause", "NOT_FOUND_AT_CLEARING");
    double concluded = count(CONCLUDED, "type", "EXTERNAL", "status", "REJECTED");
    double reconciliations = count(RECONCILIATIONS);

    int applied = reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);

    assertThat(applied).isEqualTo(1);
    assertThat(externalStatus(transferId)).isEqualTo("REJECTED");
    assertThat(count(RECONCILIATIONS, "outcome", "NOT_FOUND")).isEqualTo(notFound + 1);
    assertThat(count(REFUNDS, "cause", "NOT_FOUND_AT_CLEARING")).isEqualTo(refund + 1);
    assertThat(count(CONCLUDED, "type", "EXTERNAL", "status", "REJECTED")).isEqualTo(concluded + 1);
    assertThat(count(RECONCILIATIONS)).isEqualTo(reconciliations + 1);
  }

  @Test
  void m6_secondRoundOnTheSameTransfer_countsNothingMoreAndNoRefund() {
    drainInProgressTransfers();
    sentButNeverSubmitted(EXTERNAL_AMOUNT);
    reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);
    Map<String, Double> before = counterSnapshot();

    int applied = reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);

    assertThat(applied).isZero();
    // nothing is left in progress: no id is examined, so not even a skipped outcome is counted
    assertThat(counterSnapshot()).isEqualTo(before);
  }

  @Test
  void m6_sentTransferKnownToTheClearingHouseWithAnAcceptedStatus_reconciliationCompleted() throws Exception {
    drainInProgressTransfers();
    postTransfer(EXTERNAL_IBAN, EXTERNAL_AMOUNT, 202);
    double completed = count(RECONCILIATIONS, "outcome", "COMPLETED");
    double concluded = count(CONCLUDED, "type", "EXTERNAL", "status", "COMPLETED");

    int applied = reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);

    assertThat(applied).isEqualTo(1);
    assertThat(count(RECONCILIATIONS, "outcome", "COMPLETED")).isEqualTo(completed + 1);
    assertThat(count(CONCLUDED, "type", "EXTERNAL", "status", "COMPLETED")).isEqualTo(concluded + 1);
  }

  // ------------------------------------------------------------------ M7: notifiche pubblicate

  @Test
  void m7_publishPending_publishedGrowsByTheNumberOfSuccessfulFakeSends() throws Exception {
    generateTraffic();
    assertThat(outboxRows()).isGreaterThanOrEqualTo(4);
    double published = count(PUBLISHED);
    int sentBefore = SENT_IDS.size();

    var result = publishPendingNotifications.publishPending(MAX);

    int sentNow = SENT_IDS.size() - sentBefore;
    assertThat(result.failed()).isFalse();
    assertThat(sentNow).isGreaterThanOrEqualTo(4);
    assertThat(count(PUBLISHED)).isEqualTo(published + sentNow);
  }

  @Test
  void m7_aFailingSend_isNotCountedAndTheRowIsCountedWhenItIsFinallyPublished() throws Exception {
    // clear whatever is left in the outbox, then one single new row
    publishPendingNotifications.publishPending(MAX);
    MvcResult result = postTransfer(internalDest, COMPLETED_AMOUNT, 200);
    String transferId = JsonPath.read(result.getResponse().getContentAsString(), "$.transferId");
    FAILING_SEND_IDS.add(transferId);
    double published = count(PUBLISHED);

    var failed = publishPendingNotifications.publishPending(MAX);

    assertThat(failed.failed()).isTrue();
    assertThat(count(PUBLISHED)).isEqualTo(published);

    FAILING_SEND_IDS.clear();
    var ok = publishPendingNotifications.publishPending(MAX);

    assertThat(ok.failed()).isFalse();
    assertThat(count(PUBLISHED)).isEqualTo(published + 1);
  }

  @Test
  void m7_nothingPending_publishedDoesNotChange() {
    publishPendingNotifications.publishPending(MAX);
    double published = count(PUBLISHED);

    publishPendingNotifications.publishPending(MAX);

    assertThat(count(PUBLISHED)).isEqualTo(published);
  }

  // ------------------------------------------------------------------ M8: gauge

  @Test
  void m8_refreshPendingNotifications_theGaugeEqualsTheCountOfTheOutboxTable() throws Exception {
    generateTraffic();

    // the scheduler is on and sets the same value: retry until the comparison holds at the same instant
    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      refreshOutboxMetrics.refreshPendingNotifications();
      assertThat(gauge()).isEqualTo((double) outboxRows());
    });
    assertThat(gauge()).isGreaterThanOrEqualTo(4.0);
  }

  @Test
  void m8_afterPublishingEverything_theGaugeGoesBackToZero() throws Exception {
    postTransfer(internalDest, COMPLETED_AMOUNT, 200);
    publishPendingNotifications.publishPending(MAX);

    await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
      refreshOutboxMetrics.refreshPendingNotifications();
      assertThat(outboxRows()).isZero();
      assertThat(gauge()).isZero();
    });
  }

  // ------------------------------------------------------------------ M9: tag

  @Test
  void m9_allBusinessMeters_haveApplicationTransferAndOnlyEnumTags() throws Exception {
    generateTraffic();
    refreshOutboxMetrics.refreshPendingNotifications();

    List<Meter> meters = registry.getMeters().stream()
      .filter(meter -> meter.getId().getName().startsWith("bank.transfer.")).toList();
    assertThat(meters).isNotEmpty();
    for (Meter meter : meters) {
      assertThat(meter.getId().getTag("application")).as(meter.getId().toString()).isEqualTo("transfer");
      for (Tag tag : meter.getId().getTags()) {
        assertThat(tag.getKey()).isIn("application", "type", "status", "cause", "outcome");
        if (!tag.getKey().equals("application")) {
          assertThat(tag.getValue()).matches(ENUM_NAME);
        }
        assertThat(IBAN_LIKE.matcher(tag.getValue()).find()).as(tag.getValue()).isFalse();
        assertThat(tag.getValue()).doesNotContain(source).doesNotContain(internalDest).doesNotContain(EXTERNAL_IBAN);
        RECOGNIZABLE_AMOUNTS.forEach(amount -> assertThat(tag.getValue()).doesNotContain(amount));
        transferIds.forEach(id -> assertThat(tag.getValue()).doesNotContain(id));
      }
    }
  }

  @Test
  void m9_theSixFamiliesOfMetersExist() {
    assertThat(registry.find(REQUESTS).counters()).isNotEmpty();
    assertThat(registry.find(CONCLUDED).counters()).isNotEmpty();
    assertThat(registry.find(REFUNDS).counters()).hasSize(3);
    assertThat(registry.find(RECONCILIATIONS).counters()).hasSize(5);
    assertThat(registry.find(PUBLISHED).counters()).hasSize(1);
    assertThat(registry.find(PENDING).gauges()).hasSize(1);
  }

  // ------------------------------------------------------------------ M10: /actuator/prometheus

  /** The lines of the scrape that start with the metric name followed by its tags. */
  private static List<String> linesOf(String scrape, String prometheusName) {
    return scrape.lines().filter(line -> line.startsWith(prometheusName + "{")).toList();
  }

  private static boolean hasTags(String line, String... keyValues) {
    for (int i = 0; i < keyValues.length; i += 2) {
      if (!line.contains(keyValues[i] + "=\"" + keyValues[i + 1] + "\"")) {
        return false;
      }
    }
    return true;
  }

  private static double valueOf(String line) {
    return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
  }

  @Test
  void m10_actuatorPrometheus_200WithTheBusinessSeriesAndNoTransferData() throws Exception {
    generateTraffic();
    publishPendingNotifications.publishPending(MAX);
    refreshOutboxMetrics.refreshPendingNotifications();

    String scrape = mockMvc.perform(get("/actuator/prometheus").accept(MediaType.TEXT_PLAIN))
      .andExpect(status().isOk())
      .andReturn().getResponse().getContentAsString();

    List<String> requestLines = linesOf(scrape, "bank_transfer_requests_total");
    assertThat(requestLines).anySatisfy(line -> {
      assertThat(hasTags(line, "application", "transfer", "status", "COMPLETED", "type", "INTERNAL")).isTrue();
      assertThat(valueOf(line)).isGreaterThanOrEqualTo(1.0);
    });
    assertThat(requestLines).anySatisfy(line ->
      assertThat(hasTags(line, "application", "transfer", "status", "SENT", "type", "EXTERNAL")).isTrue());
    assertThat(linesOf(scrape, "bank_transfer_concluded_total")).anySatisfy(line -> {
      assertThat(hasTags(line, "application", "transfer", "status", "COMPLETED", "type", "EXTERNAL")).isTrue();
      assertThat(valueOf(line)).isGreaterThanOrEqualTo(1.0);
    });
    assertThat(linesOf(scrape, "bank_transfer_refunds_total")).hasSize(3);
    assertThat(linesOf(scrape, "bank_transfer_reconciliations_total")).hasSize(5);
    assertThat(scrape.lines().filter(line -> line.startsWith("bank_transfer_outbox_published_total")))
      .anySatisfy(line -> assertThat(line).contains("application=\"transfer\""));
    assertThat(linesOf(scrape, "bank_transfer_outbox_pending")).hasSize(1).allSatisfy(line ->
      assertThat(hasTags(line, "application", "transfer")).isTrue());

    List<String> bankLines = scrape.lines().filter(line -> line.startsWith("bank_")).toList();
    assertThat(bankLines).isNotEmpty();
    for (String line : bankLines) {
      assertThat(IBAN_LIKE.matcher(line).find()).as(line).isFalse();
      assertThat(line).doesNotContain(source).doesNotContain(internalDest).doesNotContain(EXTERNAL_IBAN);
      RECOGNIZABLE_AMOUNTS.forEach(amount -> assertThat(line).doesNotContain(amount));
      transferIds.forEach(id -> assertThat(line).doesNotContain(id));
    }
  }

  // ------------------------------------------------------------------ M11: output

  @Test
  void m11_duringTheWholeScenario_theOutputHasNoIbanNorAmount(CapturedOutput output) throws Exception {
    String before = output.getAll();

    generateTraffic();
    postTransfer(IBAN_PREFIX + "X99999", COMPLETED_AMOUNT, 404);
    postTransfer(internalDest, "9876.54", 200);
    simulator.setMode(SimulatedClearingModeEnum.UNAVAILABLE);
    postTransfer(EXTERNAL_IBAN, EXTERNAL_AMOUNT, 200);
    drainInProgressTransfers();
    sentButNeverSubmitted(EXTERNAL_AMOUNT);
    reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);
    publishPendingNotifications.publishPending(MAX);
    refreshOutboxMetrics.refreshPendingNotifications();
    mockMvc.perform(get("/actuator/prometheus").accept(MediaType.TEXT_PLAIN)).andExpect(status().isOk());

    String delta = output.getAll().substring(before.length());
    assertThat(delta).isNotEmpty();
    assertThat(delta).doesNotContain(source).doesNotContain(internalDest).doesNotContain(EXTERNAL_IBAN);
    RECOGNIZABLE_AMOUNTS.forEach(amount -> assertThat(delta).doesNotContain(amount));
    assertThat(IBAN_LIKE.matcher(delta).find()).as("an IBAN-like text in the output").isFalse();
  }
}
