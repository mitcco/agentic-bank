package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conteggio delle righe dell'outbox in attesa (piano transfer-14, D4). Nessuna transazione di test: ogni chiamata
 * dell'adapter apre la sua, come in esercizio. Le righe si scrivono con JdbcTemplate.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaPendingNotificationCountOutPortImpl.class)
class JpaPendingNotificationCountOutPortImplTest {

  private static final String T1 = "bbbbbbbb-0000-4000-8000-000000000001";
  private static final String T2 = "bbbbbbbb-0000-4000-8000-000000000002";
  private static final String T3 = "bbbbbbbb-0000-4000-8000-000000000003";

  @Autowired
  private JpaPendingNotificationCountOutPortImpl adapter;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  @AfterEach
  void cleanOutbox() {
    jdbcTemplate.update("DELETE FROM outbox");
  }

  private void insert(String transferId) {
    jdbcTemplate.update("INSERT INTO outbox (transfer_id, payload) VALUES (?, ?)", transferId,
      "{\"transferId\":\"" + transferId + "\",\"status\":\"COMPLETED\"}");
  }

  @Test
  void emptyTableCountsZero() {
    assertThat(adapter.countPending()).isZero();
  }

  @Test
  void threeRowsCountThree() {
    insert(T1);
    insert(T2);
    insert(T3);

    assertThat(adapter.countPending()).isEqualTo(3);
  }

  @Test
  void afterDeletingOneRowTheCountIsTwo() {
    insert(T1);
    insert(T2);
    insert(T3);
    assertThat(adapter.countPending()).isEqualTo(3);

    jdbcTemplate.update("DELETE FROM outbox WHERE transfer_id = ?", T2);

    assertThat(adapter.countPending()).isEqualTo(2);
  }

  @Test
  void afterDeletingEverythingTheCountIsZeroAgain() {
    insert(T1);
    jdbcTemplate.update("DELETE FROM outbox");

    assertThat(adapter.countPending()).isZero();
  }

  @Test
  void theCountDoesNotChangeTheTable() {
    insert(T1);
    insert(T2);

    adapter.countPending();
    adapter.countPending();

    Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);
    assertThat(rows).isEqualTo(2);
  }
}
