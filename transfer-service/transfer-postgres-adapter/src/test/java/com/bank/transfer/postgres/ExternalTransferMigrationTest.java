package com.bank.transfer.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class ExternalTransferMigrationTest {

  private static final String TRANSFER_ID = "11111111-1111-1111-1111-111111111111";
  private static final String SOURCE_IBAN = "IT60X0542811101000000123456";
  private static final String OWN_DEST_IBAN = "IT60X0542811101000000654321";
  private static final String EXTERNAL_IBAN = "IT60X0306909606100000012345";

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void createSourceAccount() {
    jdbcTemplate.update("INSERT INTO account (id, iban, balance, currency) VALUES (?, ?, ?, ?)",
        "acc-source", SOURCE_IBAN, new BigDecimal("100"), "EUR");
  }

  @Test
  void schemaMatchesTheEntity() {
    // Il contesto si avvia con ddl-auto=validate: Hibernate confronta entita e tabella.
    List<Map<String, Object>> columns = jdbcTemplate.queryForList(
        "SELECT column_name, is_nullable FROM information_schema.columns "
            + "WHERE UPPER(table_name) = 'EXTERNAL_TRANSFER'");

    Map<String, Object> nullableByColumn = columns.stream().collect(Collectors.toMap(
        c -> value(c, "column_name").toString().toLowerCase(),
        c -> value(c, "is_nullable")));

    assertThat(nullableByColumn).containsOnlyKeys(
        "id", "source_iban", "dest_iban", "amount", "currency", "status", "reason", "created_at");
    assertThat(nullableByColumn.get("reason")).isEqualTo("YES");
    assertThat(nullableByColumn.get("status")).isEqualTo("NO");
    assertThat(nullableByColumn.get("dest_iban")).isEqualTo("NO");
  }

  @Test
  void destinationThatIsNotAnAccountIsAccepted() {
    insert(TRANSFER_ID, SOURCE_IBAN, EXTERNAL_IBAN, "SENT");

    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM external_transfer", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void unknownSourceIsRejected() {
    assertThatThrownBy(() -> insert(TRANSFER_ID, EXTERNAL_IBAN, EXTERNAL_IBAN, "SENT"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void missingStatusIsRejected() {
    assertThatThrownBy(() -> jdbcTemplate.update(
        "INSERT INTO external_transfer (id, source_iban, dest_iban, amount, currency) "
            + "VALUES (?, ?, ?, ?, ?)",
        TRANSFER_ID, SOURCE_IBAN, EXTERNAL_IBAN, new BigDecimal("10.50"), "EUR"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void reasonIsOptionalAndCreatedAtHasADefault() {
    insert(TRANSFER_ID, SOURCE_IBAN, EXTERNAL_IBAN, "SENT");

    Map<String, Object> row = jdbcTemplate.queryForList(
        "SELECT reason, created_at FROM external_transfer").get(0);

    assertThat(value(row, "reason")).isNull();
    assertThat(value(row, "created_at")).isNotNull();
  }

  @Test
  void transferTableStillRequiresAnExistingDestination() {
    assertThatThrownBy(() -> jdbcTemplate.update(
        "INSERT INTO transfer (id, source_iban, dest_iban, amount, currency, status) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        TRANSFER_ID, SOURCE_IBAN, OWN_DEST_IBAN, new BigDecimal("10.50"), "EUR", "PENDING"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insert(String id, String sourceIban, String destIban, String status) {
    jdbcTemplate.update(
        "INSERT INTO external_transfer (id, source_iban, dest_iban, amount, currency, status) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        id, sourceIban, destIban, new BigDecimal("10.50"), "EUR", status);
  }

  // H2 restituisce i nomi di colonna in maiuscolo, Postgres in minuscolo.
  private Object value(Map<String, Object> row, String column) {
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(column)) {
        return entry.getValue();
      }
    }
    return null;
  }
}
