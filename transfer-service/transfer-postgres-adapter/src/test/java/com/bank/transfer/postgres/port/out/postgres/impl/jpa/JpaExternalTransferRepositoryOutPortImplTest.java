package com.bank.transfer.postgres.port.out.postgres.impl.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@Import({JpaExternalTransferRepositoryOutPortImpl.class, JpaAccountRepositoryOutPortImpl.class})
class JpaExternalTransferRepositoryOutPortImplTest {

  private static final Iban SOURCE = new Iban("IT60X0542811101000000123456");
  private static final Iban EXTERNAL = new Iban("IT60X0306909606100000012345");
  private static final String REASON = "Rejected by clearing house";

  @Autowired
  private JpaExternalTransferRepositoryOutPortImpl repository;

  @Autowired
  private JpaAccountRepositoryOutPortImpl accountRepository;

  @Autowired
  private EntityManager entityManager;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  /**
   * external_transfer.source_iban references account.iban, so the source account must exist first.
   * The destination is deliberately not an account.
   */
  @BeforeEach
  void createSourceAccount() {
    accountRepository.save(new Account("acc-source", SOURCE, new Money(new BigDecimal("100"), "EUR")));
    flushAndClear();
  }

  private static Transfer sentTransfer() {
    return Transfer.create(SOURCE, EXTERNAL, new Money(new BigDecimal("10.50"), "EUR")).markSent();
  }

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private Object reasonColumn(String id) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList("SELECT reason FROM external_transfer WHERE id = ?", id);
    assertThat(rows).hasSize(1);
    return rows.get(0).values().iterator().next();
  }

  private int count(String table) {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    assertThat(count).isNotNull();
    return count;
  }

  @Test
  void findByIdForUpdateReturnsEmptyWhenTransferDoesNotExist() {
    assertThat(repository.findByIdForUpdate("missing")).isEmpty();
  }

  @Test
  void savesATransferToAnIbanThatIsNotAnAccount() {
    Transfer transfer = sentTransfer();

    repository.save(transfer, null);
    flushAndClear();

    assertThat(repository.findByIdForUpdate(transfer.id())).contains(transfer);
  }

  @Test
  void findByIdForUpdateLocksTheRow() {
    Transfer transfer = sentTransfer();
    repository.save(transfer, null);
    flushAndClear();

    assertThat(repository.findByIdForUpdate(transfer.id())).contains(transfer);

    Object managedEntity =
        entityManager.createQuery("select t from ExternalTransferEntity t").getSingleResult();
    assertThat(entityManager.getLockMode(managedEntity)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
  }

  @Test
  void saveReplacesTheRowWithSameIdAndStoresTheReason() {
    Transfer sent = sentTransfer();
    repository.save(sent, null);
    flushAndClear();
    Transfer rejected = sent.rejectAfterSent();

    repository.save(rejected, REASON);
    flushAndClear();

    assertThat(count("external_transfer")).isEqualTo(1);
    assertThat(repository.findByIdForUpdate(sent.id())).contains(rejected);
    assertThat(reasonColumn(sent.id())).isEqualTo(REASON);
  }

  @Test
  void nullReasonLeavesTheColumnEmpty() {
    Transfer transfer = sentTransfer();

    repository.save(transfer, null);
    flushAndClear();

    assertThat(reasonColumn(transfer.id())).isNull();
  }

  @Test
  void doesNotWriteIntoTheTransferTable() {
    repository.save(sentTransfer(), null);
    flushAndClear();

    assertThat(count("external_transfer")).isEqualTo(1);
    assertThat(count("transfer")).isZero();
  }

  @Test
  void rejectsNulls() {
    assertThatThrownBy(() -> repository.save(null, null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> repository.findByIdForUpdate(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void savesAndReadsAnUnknownTransfer() {
    Transfer sent = sentTransfer();
    repository.save(sent, null);
    flushAndClear();
    Transfer unknown = sent.markUnknown();

    repository.save(unknown, null);
    flushAndClear();

    assertThat(count("external_transfer")).isEqualTo(1);
    assertThat(repository.findByIdForUpdate(sent.id())).contains(unknown);
    assertThat(repository.findByIdForUpdate(sent.id()).orElseThrow().status().name()).isEqualTo("UNKNOWN");
  }
}
