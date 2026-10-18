package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.port.in.ReconcileExternalTransfersInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.domain.Iban;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The demo data seeded at startup, followed by the reconciliation of the stale external transfers, with the real
 * service, the real JPA adapters, the simulated clearing house and a dedicated in-memory H2 database. The schedulers
 * are off (the default): the test calls the port in by hand. No other test writes in this database, and all the
 * steps depend on the same seed, so there is a single sequential test method.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:demoreconcile;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class DemoDataReconciliationIntegrationTest {

  private static final String NOT_FOUND_REASON = "Instruction not found at the clearing house";
  private static final int MAX = 100;
  private static final String SENT_ID = "demo-ext-009";
  private static final String UNKNOWN_ID = "demo-ext-010";
  private static final String SOURCE_OF_SENT = iban(149);
  private static final String SOURCE_OF_UNKNOWN = iban(150);

  @Autowired
  private ReconcileExternalTransfersInPort reconcileExternalTransfers;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private static String iban(int accountNumber) {
    return "IT60X0542811101" + String.format("%012d", accountNumber);
  }

  private BigDecimal balance(String iban) {
    return accountRepository.findByIban(new Iban(iban)).orElseThrow().money().amount();
  }

  private Map<String, BigDecimal> balances() {
    Map<String, BigDecimal> balances = new LinkedHashMap<>();
    jdbcTemplate.query("select iban, balance from account order by iban",
      rs -> {
        balances.put(rs.getString("iban"), rs.getBigDecimal("balance"));
      });
    return balances;
  }

  /** Status and reason of every demo external transfer, by id. */
  private Map<String, String> externalRows() {
    Map<String, String> rows = new LinkedHashMap<>();
    jdbcTemplate.query("select id, status, reason from external_transfer where id like 'demo-ext-%' order by id",
      rs -> {
        rows.put(rs.getString("id"), rs.getString("status") + "|" + rs.getString("reason"));
      });
    return rows;
  }

  private int demoOutboxRows() {
    Integer count =
      jdbcTemplate.queryForObject("select count(*) from outbox where transfer_id like 'demo-%'", Integer.class);
    assertThat(count).isNotNull();
    return count;
  }

  private int outboxRows(String transferId) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from outbox where transfer_id = ?", Integer.class,
      transferId);
    assertThat(count).isNotNull();
    return count;
  }

  private String outboxPayload(String transferId) {
    return jdbcTemplate.queryForObject("select payload from outbox where transfer_id = ?", String.class,
      transferId);
  }

  private String status(String transferId) {
    return jdbcTemplate.queryForObject("select status from external_transfer where id = ?", String.class,
      transferId);
  }

  private String reason(String transferId) {
    return jdbcTemplate.queryForObject("select reason from external_transfer where id = ?", String.class,
      transferId);
  }

  private static BigDecimal sum(Map<String, BigDecimal> balances) {
    return balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static void assertSameBalancesExcept(Map<String, BigDecimal> before, Map<String, BigDecimal> after,
    String... changedIbans) {
    assertThat(after.keySet()).isEqualTo(before.keySet());
    Set<String> changed = Set.of(changedIbans);
    before.forEach((iban, amount) -> {
      if (!changed.contains(iban)) {
        assertThat(after.get(iban)).as("balance of %s", iban).isEqualByComparingTo(amount);
      }
    });
  }

  @Test
  void seededSentAndUnknownAreLeftAloneUntilStaleThenRefunded() {
    // (1) initial check: the seed is there, with the coherent balances
    Map<String, BigDecimal> seededBalances = balances();
    Map<String, String> seededExternals = externalRows();
    assertThat(seededBalances).hasSize(53);
    assertThat(seededExternals).hasSize(10);
    assertThat(balance("IT60X0542811101000000000001")).isEqualByComparingTo("1900.00");
    assertThat(balance("IT60X0542811101000000000002")).isEqualByComparingTo("2049.75");
    assertThat(balance("IT60X0542811101000000000003")).isEqualByComparingTo("2050.25");
    assertThat(balance(iban(101))).isEqualByComparingTo("1910.81");
    assertThat(balance(iban(108))).isEqualByComparingTo("1445.67");
    assertThat(balance(iban(141))).isEqualByComparingTo("1972.73");
    assertThat(balance(iban(143))).isEqualByComparingTo("1840.81");
    assertThat(balance(iban(147))).isEqualByComparingTo("2507.60");
    assertThat(balance(iban(148))).isEqualByComparingTo("2000.00");
    assertThat(balance(SOURCE_OF_SENT)).isEqualByComparingTo("1542.43");
    assertThat(balance(SOURCE_OF_UNKNOWN)).isEqualByComparingTo("1492.70");
    // 53 accounts at 2000.00 (internal transfers net to zero) minus the 6 external transfers that hold money
    // (001-004, 009, 010): 59.73 + 109.46 + 159.19 + 208.92 + 457.57 + 507.30 = 1502.17
    assertThat(sum(seededBalances)).isEqualByComparingTo("104497.83");
    assertThat(status(SENT_ID)).isEqualTo("SENT");
    assertThat(status(UNKNOWN_ID)).isEqualTo("UNKNOWN");
    assertThat(demoOutboxRows()).isZero();

    // (2) nothing is stale yet: created_at is the instant of the seed, later than one hour ago
    reconcileExternalTransfers.reconcileStale(Instant.now().minus(Duration.ofHours(1)), MAX);

    assertThat(status(SENT_ID)).isEqualTo("SENT");
    assertThat(status(UNKNOWN_ID)).isEqualTo("UNKNOWN");
    assertThat(externalRows()).isEqualTo(seededExternals);
    assertSameBalancesExcept(seededBalances, balances());
    assertThat(balance(SOURCE_OF_SENT)).isEqualByComparingTo("1542.43");
    assertThat(balance(SOURCE_OF_UNKNOWN)).isEqualByComparingTo("1492.70");
    assertThat(demoOutboxRows()).isZero();

    // (3) everything is stale: the clearing house never received them, so they are refunded
    int reconciled = reconcileExternalTransfers.reconcileStale(Instant.now().plusSeconds(1), MAX);

    assertThat(reconciled).isGreaterThanOrEqualTo(2);
    assertThat(status(SENT_ID)).isEqualTo("REJECTED");
    assertThat(reason(SENT_ID)).isEqualTo(NOT_FOUND_REASON);
    assertThat(status(UNKNOWN_ID)).isEqualTo("REJECTED");
    assertThat(reason(UNKNOWN_ID)).isEqualTo(NOT_FOUND_REASON);
    assertThat(balance(SOURCE_OF_SENT)).isEqualByComparingTo("2000.00");
    assertThat(balance(SOURCE_OF_UNKNOWN)).isEqualByComparingTo("2000.00");
    assertThat(outboxRows(SENT_ID)).isEqualTo(1);
    assertThat(outboxPayload(SENT_ID)).contains("\"status\":\"REJECTED\"");
    assertThat(outboxRows(UNKNOWN_ID)).isEqualTo(1);
    assertThat(outboxPayload(UNKNOWN_ID)).contains("\"status\":\"REJECTED\"");
    assertThat(demoOutboxRows()).isEqualTo(2);
    Map<String, String> expectedExternals = new LinkedHashMap<>(seededExternals);
    expectedExternals.put(SENT_ID, "REJECTED|" + NOT_FOUND_REASON);
    expectedExternals.put(UNKNOWN_ID, "REJECTED|" + NOT_FOUND_REASON);
    assertThat(externalRows()).isEqualTo(expectedExternals);
    Map<String, BigDecimal> reconciledBalances = balances();
    assertSameBalancesExcept(seededBalances, reconciledBalances, SOURCE_OF_SENT, SOURCE_OF_UNKNOWN);
    // the two refunds give back 457.57 and 507.30
    assertThat(sum(reconciledBalances)).isEqualByComparingTo("105462.70");

    // (4) a second round changes nothing for the demo ids: idempotent
    reconcileExternalTransfers.reconcileStale(Instant.now().plusSeconds(1), MAX);

    assertThat(externalRows()).isEqualTo(expectedExternals);
    assertSameBalancesExcept(reconciledBalances, balances());
    assertThat(balance(SOURCE_OF_SENT)).isEqualByComparingTo("2000.00");
    assertThat(balance(SOURCE_OF_UNKNOWN)).isEqualByComparingTo("2000.00");
    assertThat(outboxRows(SENT_ID)).isEqualTo(1);
    assertThat(outboxRows(UNKNOWN_ID)).isEqualTo(1);
    assertThat(demoOutboxRows()).isEqualTo(2);
  }
}
