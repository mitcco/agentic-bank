package com.bank.transfer.postgres.port.out.postgres.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.postgres.port.out.postgres.impl.jpa.JpaAccountRepositoryOutPortImpl;
import com.bank.transfer.postgres.port.out.postgres.impl.jpa.JpaExternalTransferRepositoryOutPortImpl;
import com.bank.transfer.postgres.port.out.postgres.impl.jpa.JpaTransferRepositoryOutPortImpl;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@Import({JpaAccountRepositoryOutPortImpl.class, JpaTransferRepositoryOutPortImpl.class,
  JpaExternalTransferRepositoryOutPortImpl.class, DemoDataSeeder.class})
class DemoDataSeederTest {

  private static final Iban ACC1 = new Iban("IT60X0542811101000000000001");
  private static final Iban ACC2 = new Iban("IT60X0542811101000000000002");
  private static final Iban ACC3 = new Iban("IT60X0542811101000000000003");

  @Autowired
  private DemoDataSeeder seeder;

  @Autowired
  private JpaAccountRepositoryOutPortImpl accounts;

  @Autowired
  private JpaTransferRepositoryOutPortImpl transfers;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private EntityManager entityManager;

  private static Money eur(String amount) {
    return new Money(new BigDecimal(amount), "EUR");
  }

  @Test
  void loadsDemoAccountsAndTransfers() {
    seeder.run(null);

    assertThat(accounts.findByIban(ACC1)).contains(new Account("ac1", ACC1, eur("1900.00")));
    assertThat(accounts.findByIban(ACC2)).contains(new Account("ac2", ACC2, eur("2049.75")));
    assertThat(accounts.findByIban(ACC3)).contains(new Account("ac3", ACC3, eur("2050.25")));
    assertThat(transfers.findById("tr1"))
      .contains(new Transfer("tr1", ACC1, ACC2, eur("100.00"), TransferStatusEnum.COMPLETED));
    assertThat(transfers.findById("tr2"))
      .contains(new Transfer("tr2", ACC2, ACC3, eur("50.25"), TransferStatusEnum.COMPLETED));
    assertThat(transfers.findById("tr3"))
      .contains(new Transfer("tr3", ACC3, ACC1, eur("15000.00"), TransferStatusEnum.REJECTED));
  }

  @Test
  void doesNotOverwriteExistingDataWhenRunAgain() {
    seeder.run(null);
    accounts.save(new Account("ac1", ACC1, eur("1.00")));

    seeder.run(null);

    assertThat(accounts.findByIban(ACC1)).contains(new Account("ac1", ACC1, eur("1.00")));
  }

  // ------------------------------------------------------------------ helpers of the new tests

  private void flushAndClear() {
    entityManager.flush();
    entityManager.clear();
  }

  private void seed() {
    seeder.run(null);
    flushAndClear();
  }

  private int count(String table) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    assertThat(count).isNotNull();
    return count;
  }

  private BigDecimal storedBalance(String iban) {
    return jdbcTemplate.queryForObject("select balance from account where iban = ?", BigDecimal.class, iban);
  }

  private List<Map<String, Object>> snapshot(String sql) {
    return jdbcTemplate.queryForList(sql);
  }

  @Test
  void loadsTheWholeCatalog() {
    seed();

    assertThat(count("account")).isEqualTo(53);
    assertThat(count("transfer")).isEqualTo(43);
    assertThat(count("external_transfer")).isEqualTo(10);
  }

  @Test
  void storedBalancesMatchTheCatalog() {
    seed();

    for (Account expected : DemoDataCatalog.accounts()) {
      assertThat(accounts.findByIban(expected.iban())).contains(expected);
      assertThat(storedBalance(expected.iban().value()))
        .as("balance of %s", expected.id())
        .isEqualByComparingTo(expected.money().amount());
    }
    assertThat(storedBalance("IT60X0542811101000000000149")).isEqualByComparingTo("1542.43");
    assertThat(storedBalance("IT60X0542811101000000000150")).isEqualByComparingTo("1492.70");
  }

  @Test
  void externalRowsKeepStatusAndReason() {
    seed();

    assertExternal("demo-ext-005", "REJECTED", "Rejected by the clearing house");
    assertExternal("demo-ext-008", "REJECTED", "Amount must not exceed 10000 EUR");
    assertExternal("demo-ext-009", "SENT", null);
    assertExternal("demo-ext-010", "UNKNOWN", null);
  }

  private void assertExternal(String id, String status, String reason) {
    Map<String, Object> row =
      jdbcTemplate.queryForMap("select status, reason from external_transfer where id = ?", id);
    assertThat(row.get("STATUS")).isEqualTo(status);
    assertThat(row.get("REASON")).isEqualTo(reason);
  }

  @Test
  void runningTwiceChangesNothing() {
    seed();
    List<Map<String, Object>> accountsBefore = snapshot("select * from account order by id");
    List<Map<String, Object>> transfersBefore = snapshot("select * from transfer order by id");
    List<Map<String, Object>> externalBefore = snapshot("select * from external_transfer order by id");

    seed();

    assertThat(snapshot("select * from account order by id")).isEqualTo(accountsBefore);
    assertThat(snapshot("select * from transfer order by id")).isEqualTo(transfersBefore);
    assertThat(snapshot("select * from external_transfer order by id")).isEqualTo(externalBefore);
    assertThat(count("account")).isEqualTo(53);
    assertThat(count("transfer")).isEqualTo(43);
    assertThat(count("external_transfer")).isEqualTo(10);
  }

  @Test
  void fillsOnlyTheMissingRowsOfAPartiallySeededDatabase() {
    Iban account101 = new Iban("IT60X0542811101000000000101");
    seed();
    jdbcTemplate.update("delete from transfer where id = ?", "demo-tr-001");
    jdbcTemplate.update("delete from external_transfer where id = ?", "demo-ext-001");
    entityManager.clear();
    accounts.save(new Account("demo-acc-101", account101, eur("1.00")));
    flushAndClear();
    assertThat(count("transfer")).isEqualTo(42);
    assertThat(count("external_transfer")).isEqualTo(9);

    seed();

    assertThat(count("transfer")).isEqualTo(43);
    assertThat(count("external_transfer")).isEqualTo(10);
    assertThat(transfers.findById("demo-tr-001")).isPresent();
    assertExternal("demo-ext-001", "COMPLETED", null);
    assertThat(storedBalance(account101.value())).isEqualByComparingTo("1.00");
    assertThat(count("account")).isEqualTo(53);
  }

  @Test
  void writesNoOutboxRows() {
    seed();

    assertThat(count("outbox")).isZero();
  }
}
