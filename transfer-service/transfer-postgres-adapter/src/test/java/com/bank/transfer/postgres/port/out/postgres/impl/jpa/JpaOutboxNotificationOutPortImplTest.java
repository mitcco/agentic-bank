package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.domain.TransferStatusEnum;
import com.bank.transfer.postgres.repository.SpringDataOutboxRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@Import({JpaOutboxNotificationOutPortImpl.class,
  JpaOutboxNotificationOutPortImplTest.FixedClockConfig.class})
class JpaOutboxNotificationOutPortImplTest {

  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0542811101000000000002";
  private static final List<String> REASONS =
    List.of("Amount must not exceed 10000 EUR", "Source IBAN is locked");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-10-13T09:30:00Z"), ZoneOffset.UTC);
    }
  }

  @Autowired
  private JpaOutboxNotificationOutPortImpl adapter;

  @Autowired
  private SpringDataOutboxRepository outboxRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private EntityManager entityManager;

  // 1
  @Test
  void completedNotificationIsWrittenWithTheContractFieldsAndNoReasons() throws Exception {
    adapter.publish(completed("100.25"));
    entityManager.flush();

    List<Map<String, Object>> rows = rows();
    assertThat(rows).hasSize(1);
    assertThat(value(rows.get(0), "transfer_id")).isEqualTo(TRANSFER_ID);
    JsonNode json = JSON.readTree(payload(rows.get(0)));
    List<String> keys = new ArrayList<>();
    json.propertyNames().forEach(keys::add);
    assertThat(keys).containsExactlyInAnyOrder("transferId", "sourceIban", "destinationIban",
      "amount", "currency", "status", "occurredAt");
    assertThat(json.has("reasons")).isFalse();
    assertThat(json.has("destIban")).isFalse();
    assertThat(json.get("transferId").asString()).isEqualTo(TRANSFER_ID);
    assertThat(json.get("sourceIban").asString()).isEqualTo(SOURCE_IBAN);
    assertThat(json.get("destinationIban").asString()).isEqualTo(DEST_IBAN);
    assertThat(json.get("currency").asString()).isEqualTo("EUR");
    assertThat(json.get("status").asString()).isEqualTo("COMPLETED");
    assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-13T09:30:00Z");
  }

  // 2
  @Test
  void amountIsWrittenAsANumericJsonNodeNotAsText() throws Exception {
    adapter.publish(completed("100.25"));
    entityManager.flush();

    String payload = payload(rows().get(0));
    JsonNode amount = JSON.readTree(payload).get("amount");
    assertThat(amount.isNumber()).isTrue();
    assertThat(amount.decimalValue()).isEqualByComparingTo("100.25");
    assertThat(payload).contains("\"amount\":100.25");
  }

  // 3
  @Test
  void rejectedNotificationCarriesTheReasonsInOrder() throws Exception {
    adapter.publish(rejected("20000.00"));
    entityManager.flush();

    String payload = payload(rows().get(0));
    JsonNode json = JSON.readTree(payload);
    assertThat(json.get("status").asString()).isEqualTo("REJECTED");
    assertThat(json.get("reasons").isArray()).isTrue();
    List<String> reasons = new ArrayList<>();
    json.get("reasons").forEach(r -> reasons.add(r.asString()));
    assertThat(reasons).containsExactlyElementsOf(REASONS);
    assertThat(payload).contains("\"amount\":20000.00");
  }

  // 4
  @Test
  void amountIsNormalizedToTwoDecimals() {
    adapter.publish(completed("100.2"));
    adapter.publish(completed("20000"));
    adapter.publish(completed("100.2500"));
    entityManager.flush();

    List<Map<String, Object>> rows = rows();
    assertThat(rows).hasSize(3);
    assertThat(payload(rows.get(0))).contains("\"amount\":100.20");
    assertThat(payload(rows.get(1))).contains("\"amount\":20000.00");
    assertThat(payload(rows.get(2))).contains("\"amount\":100.25");
  }

  // 5
  @Test
  void occurredAtIsTruncatedToSecondsAndTakenFromTheClock() throws Exception {
    Clock withNanos = Clock.fixed(Instant.parse("2026-10-13T09:30:00.123456789Z"), ZoneOffset.UTC);
    // direct instantiation: the transaction of the test is enough, no proxy is needed here
    JpaOutboxNotificationOutPortImpl custom =
      new JpaOutboxNotificationOutPortImpl(outboxRepository, withNanos);

    custom.publish(completed("100.25"));
    entityManager.flush();

    JsonNode json = JSON.readTree(payload(rows().get(0)));
    assertThat(json.get("occurredAt").asString()).isEqualTo("2026-10-13T09:30:00Z");
  }

  // 6
  @Test
  void twoPublishesCreateTwoRowsWithIncreasingIdsEvenWithTheSameTransferId() {
    adapter.publish(completed("100.25"));
    adapter.publish(completed("100.25"));
    entityManager.flush();

    List<Map<String, Object>> rows = rows();
    assertThat(rows).hasSize(2);
    assertThat(value(rows.get(0), "transfer_id")).isEqualTo(TRANSFER_ID);
    assertThat(value(rows.get(1), "transfer_id")).isEqualTo(TRANSFER_ID);
    assertThat(((Number) value(rows.get(0), "id")).longValue())
      .isLessThan(((Number) value(rows.get(1), "id")).longValue());
  }

  // 7
  @Test
  void createdAtIsFilledByTheDatabase() {
    adapter.publish(completed("100.25"));
    entityManager.flush();

    assertThat(value(rows().get(0), "created_at")).isNotNull();
  }

  // 8
  @Test
  void nullNotificationIsRejectedWithoutWritingAnyRow() {
    assertThatThrownBy(() -> adapter.publish(null)).isInstanceOf(NullPointerException.class);

    assertThat(count()).isZero();
  }

  // 9
  @Test
  void amountWithMoreThanTwoSignificantDecimalsIsRejectedWithoutWritingAnyRow() {
    assertThatThrownBy(() -> adapter.publish(completed("1.234")))
      .isInstanceOf(IllegalArgumentException.class)
      .satisfies(e -> assertMessageHasNoSensitiveData(e, "1.234"));

    assertThat(count()).isZero();
  }

  // 10
  @Test
  void currencyThatIsNotThreeUppercaseLettersIsRejectedWithoutWritingAnyRow() {
    for (String currency : List.of("EURO", "eur", "E1R")) {
      assertThatThrownBy(() -> adapter.publish(notification(currency, "100.25")))
        .as("currency %s", currency)
        .isInstanceOf(IllegalArgumentException.class)
        .satisfies(e -> assertMessageHasNoSensitiveData(e, "100.25"));
    }

    assertThat(count()).isZero();
  }

  // 11 and 13 (write error: no query after the exception, a failed Postgres transaction would abort it)
  @Test
  void transferIdLongerThanTheColumnFailsWithDataIntegrityViolationAndNoIban() {
    String tooLong = "x".repeat(37);

    assertThatThrownBy(() -> adapter.publish(new TransferNotificationDto(tooLong, SOURCE_IBAN,
      DEST_IBAN, new BigDecimal("100.25"), "EUR", TransferStatusEnum.COMPLETED, List.of())))
      .isInstanceOf(DataIntegrityViolationException.class)
      .satisfies(e -> assertThat(String.valueOf(e.getMessage()))
        .doesNotContain(SOURCE_IBAN, DEST_IBAN));
  }

  // 13
  @Test
  void errorMessagesNeverContainIbans() {
    assertThatThrownBy(() -> adapter.publish(completed("1.234")))
      .satisfies(e -> assertThat(String.valueOf(e.getMessage()))
        .doesNotContain(SOURCE_IBAN, DEST_IBAN));
    assertThatThrownBy(() -> adapter.publish(notification("eur", "100.25")))
      .satisfies(e -> assertThat(String.valueOf(e.getMessage()))
        .doesNotContain(SOURCE_IBAN, DEST_IBAN));
  }

  private static void assertMessageHasNoSensitiveData(Throwable e, String amount) {
    assertThat(String.valueOf(e.getMessage())).doesNotContain(SOURCE_IBAN, DEST_IBAN, amount);
  }

  private static TransferNotificationDto completed(String amount) {
    return notification("EUR", amount);
  }

  private static TransferNotificationDto notification(String currency, String amount) {
    return new TransferNotificationDto(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN,
      new BigDecimal(amount), currency, TransferStatusEnum.COMPLETED, List.of());
  }

  private static TransferNotificationDto rejected(String amount) {
    return new TransferNotificationDto(TRANSFER_ID, SOURCE_IBAN, DEST_IBAN,
      new BigDecimal(amount), "EUR", TransferStatusEnum.REJECTED, REASONS);
  }

  private List<Map<String, Object>> rows() {
    return jdbcTemplate.queryForList(
      "SELECT id, transfer_id, payload, created_at FROM outbox ORDER BY id");
  }

  private int count() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
    assertThat(count).isNotNull();
    return count;
  }

  // payload is TEXT: H2 may return a Clob, Postgres a String
  private String payload(Map<String, Object> row) {
    Object payload = value(row, "payload");
    if (payload instanceof java.sql.Clob clob) {
      try {
        return clob.getSubString(1, (int) clob.length());
      } catch (java.sql.SQLException e) {
        throw new IllegalStateException(e);
      }
    }
    return (String) payload;
  }

  // H2 returns column names in uppercase, Postgres in lowercase.
  private Object value(Map<String, Object> row, String column) {
    return row.entrySet().stream()
      .filter(e -> e.getKey().equalsIgnoreCase(column))
      .map(Map.Entry::getValue)
      .findFirst()
      .orElse(null);
  }
}
