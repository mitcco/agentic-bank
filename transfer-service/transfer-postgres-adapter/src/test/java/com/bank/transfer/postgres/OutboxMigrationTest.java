package com.bank.transfer.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
class OutboxMigrationTest {

  private static final String TRANSFER_ID_1 = "11111111-1111-1111-1111-111111111111";
  private static final String TRANSFER_ID_2 = "22222222-2222-2222-2222-222222222222";
  private static final String PAYLOAD =
      "{\"transferId\":\"3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93\","
          + "\"sourceIban\":\"IT60X0542811101000000000001\","
          + "\"destinationIban\":\"IT60X0542811101000000000002\","
          + "\"amount\":100.25,"
          + "\"currency\":\"EUR\","
          + "\"status\":\"COMPLETED\","
          + "\"occurredAt\":\"2026-10-13T09:30:00Z\"}";

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  void insertSavesRowWithGeneratedIdAndCreatedAt() {
    insert(TRANSFER_ID_1, PAYLOAD);

    List<Map<String, Object>> rows = rows();

    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(value(row, "transfer_id")).isEqualTo(TRANSFER_ID_1);
    assertThat(value(row, "payload")).isEqualTo(PAYLOAD);
    assertThat(((Number) value(row, "id")).longValue()).isGreaterThan(0L);
    assertThat(value(row, "created_at")).isNotNull();
  }

  @Test
  void insertsAreReturnedInIdOrder() {
    insert(TRANSFER_ID_1, PAYLOAD);
    insert(TRANSFER_ID_2, PAYLOAD);

    List<Map<String, Object>> rows = rows();

    assertThat(rows).extracting(r -> value(r, "transfer_id"))
        .containsExactly(TRANSFER_ID_1, TRANSFER_ID_2);
    assertThat(((Number) value(rows.get(0), "id")).longValue())
        .isLessThan(((Number) value(rows.get(1), "id")).longValue());
  }

  @Test
  void insertWithoutTransferIdIsRejected() {
    assertThatThrownBy(() ->
        jdbcTemplate.update("INSERT INTO outbox (payload) VALUES (?)", PAYLOAD))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insertWithExplicitNullTransferIdIsRejected() {
    assertThatThrownBy(() ->
        jdbcTemplate.update("INSERT INTO outbox (transfer_id, payload) VALUES (NULL, ?)", PAYLOAD))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insertWithoutPayloadIsRejected() {
    assertThatThrownBy(() ->
        jdbcTemplate.update("INSERT INTO outbox (transfer_id) VALUES (?)", TRANSFER_ID_1))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void insertWithExplicitNullPayloadIsRejected() {
    assertThatThrownBy(() ->
        jdbcTemplate.update("INSERT INTO outbox (transfer_id, payload) VALUES (?, NULL)",
            TRANSFER_ID_1))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deleteByIdRemovesTheRow() {
    insert(TRANSFER_ID_1, PAYLOAD);
    Number id = (Number) value(rows().get(0), "id");

    int deleted = jdbcTemplate.update("DELETE FROM outbox WHERE id = ?", id.longValue());

    assertThat(deleted).isEqualTo(1);
    assertThat(count()).isEqualTo(0);
  }

  @Test
  void sameTransferIdCanBeInsertedTwice() {
    // Nessun vincolo UNIQUE su transfer_id: voluto, per la consegna at-least-once
    // (contratto transfer-notification, sezione 5): una ripubblicazione e' ammessa.
    insert(TRANSFER_ID_1, PAYLOAD);
    insert(TRANSFER_ID_1, PAYLOAD);

    List<Map<String, Object>> rows = rows();

    assertThat(count()).isEqualTo(2);
    assertThat(((Number) value(rows.get(0), "id")).longValue())
        .isNotEqualTo(((Number) value(rows.get(1), "id")).longValue());
  }

  private void insert(String transferId, String payload) {
    jdbcTemplate.update("INSERT INTO outbox (transfer_id, payload) VALUES (?, ?)",
        transferId, payload);
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

  // H2 restituisce i nomi di colonna in maiuscolo, Postgres in minuscolo.
  private Object value(Map<String, Object> row, String column) {
    return row.entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(column))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }
}
