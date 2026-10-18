package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The query of the external transfers that are still in progress (SENT or UNKNOWN) and older than a threshold.
 * The ages are set by hand on created_at with JDBC, from a fixed reference instant, so no test depends on the clock.
 */
@DataJpaTest
@Import({JpaExternalTransferRepositoryOutPortImpl.class, JpaAccountRepositoryOutPortImpl.class})
class JpaExternalTransferStaleQueryTest {

  private static final Iban SOURCE = new Iban("IT60X0542811101000000123456");
  private static final Iban EXTERNAL = new Iban("IT60X0306909606100000012345");
  /** Fixed reference "now" of the tests, in UTC. */
  private static final LocalDateTime BASE = LocalDateTime.of(2026, 3, 10, 12, 0, 0);
  private static final Instant THRESHOLD = BASE.toInstant(ZoneOffset.UTC);

  @Autowired
  private JpaExternalTransferRepositoryOutPortImpl repository;

  @Autowired
  private JpaAccountRepositoryOutPortImpl accountRepository;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void createSourceAccount() {
    accountRepository.save(new Account("acc-source", SOURCE, new Money(new BigDecimal("100"), "EUR")));
    flushAndClear();
  }

  // ------------------------------------------------------------------ helpers

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private static Transfer sent() {
    return Transfer.create(SOURCE, EXTERNAL, new Money(new BigDecimal("10.50"), "EUR")).markSent();
  }

  /** Saves the transfer, then sets its created_at (UTC) to the given value. */
  private Transfer saved(Transfer transfer, LocalDateTime createdAtUtc) {
    repository.save(transfer, null);
    flushAndClear();
    jdbcTemplate.update("UPDATE external_transfer SET created_at = ? WHERE id = ?",
      Timestamp.valueOf(createdAtUtc), transfer.id());
    return transfer;
  }

  private LocalDateTime createdAtOf(String id) {
    Timestamp createdAt =
      jdbcTemplate.queryForObject("SELECT created_at FROM external_transfer WHERE id = ?", Timestamp.class, id);
    assertThat(createdAt).isNotNull();
    return createdAt.toLocalDateTime();
  }

  // ------------------------------------------------------------------ tests

  @Test
  void returnsOnlySentAndUnknownCreatedBeforeTheThreshold() {
    LocalDateTime anHourAgo = BASE.minusHours(1);
    Transfer sent = saved(sent(), anHourAgo);
    Transfer unknown = saved(sent().markUnknown(), anHourAgo);
    saved(sent().settle(), anHourAgo);
    saved(sent().rejectAfterSent(), anHourAgo);

    List<String> ids = repository.findInProgressIdsCreatedBefore(THRESHOLD, 100);

    assertThat(ids).containsExactlyInAnyOrder(sent.id(), unknown.id());
  }

  @Test
  void excludesRowsCreatedAfterTheThreshold() {
    Transfer old = saved(sent(), BASE.minusMinutes(1));
    Transfer recent = saved(sent(), BASE.plusMinutes(1));
    Transfer recentUnknown = saved(sent().markUnknown(), BASE.plusHours(1));

    List<String> ids = repository.findInProgressIdsCreatedBefore(THRESHOLD, 100);

    assertThat(ids).contains(old.id()).doesNotContain(recent.id(), recentUnknown.id());
  }

  @Test
  void excludesARowCreatedExactlyAtTheThreshold() {
    Transfer atTheThreshold = saved(sent(), BASE);
    Transfer justBefore = saved(sent(), BASE.minusSeconds(1));

    List<String> ids = repository.findInProgressIdsCreatedBefore(THRESHOLD, 100);

    assertThat(ids).contains(justBefore.id()).doesNotContain(atTheThreshold.id());
  }

  @Test
  void oldestFirstAndLimitedToMax() {
    Transfer twoHours = saved(sent(), BASE.minusHours(2));
    Transfer threeHours = saved(sent(), BASE.minusHours(3));
    saved(sent(), BASE.minusHours(1));

    List<String> ids = repository.findInProgressIdsCreatedBefore(THRESHOLD, 2);

    assertThat(ids).containsExactly(threeHours.id(), twoHours.id());
  }

  @Test
  void nothingStaleGivesAnEmptyList() {
    saved(sent(), BASE.plusMinutes(5));

    assertThat(repository.findInProgressIdsCreatedBefore(THRESHOLD, 10)).isEmpty();
  }

  @Test
  void createdAtIsSetInUtcOnInsert() {
    LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
    Transfer transfer = sent();

    repository.save(transfer, null);
    flushAndClear();
    LocalDateTime after = LocalDateTime.now(ZoneOffset.UTC);

    // a few seconds of tolerance; a database default in the zone of the JVM would be off by hours
    assertThat(createdAtOf(transfer.id())).isBetween(before.minusSeconds(5), after.plusSeconds(5));
  }

  @Test
  void createdAtNeverChangesOnLaterSaves() {
    LocalDateTime fixed = BASE.minusDays(2);
    Transfer sent = saved(sent(), fixed);
    Transfer unknown = sent.markUnknown();

    repository.save(unknown, null);
    flushAndClear();
    assertThat(createdAtOf(sent.id())).isEqualTo(fixed);

    repository.save(unknown.settle(), null);
    flushAndClear();
    assertThat(createdAtOf(sent.id())).isEqualTo(fixed);
    assertThat(repository.findByIdForUpdate(sent.id()).orElseThrow().status().name()).isEqualTo("COMPLETED");
  }

  @Test
  void rejectsInvalidArguments() {
    assertThatThrownBy(() -> repository.findInProgressIdsCreatedBefore(null, 10))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repository.findInProgressIdsCreatedBefore(THRESHOLD, 0))
      .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repository.findInProgressIdsCreatedBefore(THRESHOLD, -1))
      .isInstanceOf(IllegalArgumentException.class);
  }
}
