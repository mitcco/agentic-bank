package com.bank.transfer.postgres.port.out.postgres.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The demo catalog is pure data: these tests check counts, formulas and, above all, that the balances agree with
 * a replay of the transfer history written here, independently from the code that builds the catalog.
 */
class DemoDataCatalogTest {

  private static final String OUR_ABI = "05428";
  private static final String[] OTHER_ABIS = {"03069", "02008", "05034", "01030"};
  private static final BigDecimal START = new BigDecimal("2000.00");
  private static final BigDecimal FRAUD_THRESHOLD = new BigDecimal("10000.00");

  // ------------------------------------------------------------------ helpers

  private static String fmt12(int n) {
    return String.format("%012d", n);
  }

  private static String newIban(int accountNumber) {
    return "IT60X0542811101" + fmt12(accountNumber);
  }

  private static BigDecimal amount(String value) {
    return new BigDecimal(value);
  }

  private static Account account(String id) {
    return DemoDataCatalog.accounts().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
  }

  private static Account accountByIban(String iban) {
    return DemoDataCatalog.accounts().stream().filter(a -> a.iban().value().equals(iban)).findFirst().orElseThrow();
  }

  private static Account newAccount(int number) {
    return account("demo-acc-" + number);
  }

  private static Transfer internal(String id) {
    return DemoDataCatalog.internalTransfers().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
  }

  private static DemoDataCatalog.ExternalSeed external(int k) {
    String id = String.format("demo-ext-%03d", k);
    return DemoDataCatalog.externalTransfers().stream().filter(e -> e.transfer().id().equals(id)).findFirst()
      .orElseThrow();
  }

  private static Set<String> accountIbans() {
    return DemoDataCatalog.accounts().stream().map(a -> a.iban().value()).collect(Collectors.toSet());
  }

  private static long countInternal(TransferStatusEnum status) {
    return DemoDataCatalog.internalTransfers().stream().filter(t -> t.status() == status).count();
  }

  private static List<Transfer> allTransfers() {
    return Stream.concat(DemoDataCatalog.internalTransfers().stream(),
      DemoDataCatalog.externalTransfers().stream().map(DemoDataCatalog.ExternalSeed::transfer)).toList();
  }

  // ------------------------------------------------------------------ accounts

  @Test
  void has53AccountsWithUniqueIdsAndIbans() {
    List<Account> accounts = DemoDataCatalog.accounts();

    assertThat(accounts).hasSize(53);
    assertThat(accounts.stream().map(Account::id).collect(Collectors.toSet())).hasSize(53);
    assertThat(accountIbans()).hasSize(53);
    assertThat(accounts).allSatisfy(a -> assertThat(a.money().currency()).isEqualTo("EUR"));
  }

  @Test
  void allAccountsBelongToOurBank() {
    assertThat(DemoDataCatalog.accounts())
      .allSatisfy(a -> assertThat(a.iban().bankCode().value()).isEqualTo(OUR_ABI));
  }

  @Test
  void newIbansFollowTheScheme() {
    for (int n = 101; n <= 150; n++) {
      Account account = newAccount(n);

      assertThat(account.iban().value()).isEqualTo(newIban(n)).hasSize(27);
    }
    assertThat(account("ac1").iban().value()).isEqualTo("IT60X0542811101000000000001");
    assertThat(account("ac2").iban().value()).isEqualTo("IT60X0542811101000000000002");
    assertThat(account("ac3").iban().value()).isEqualTo("IT60X0542811101000000000003");
  }

  // ------------------------------------------------------------------ internal transfers

  @Test
  void has43InternalTransfersWithTheAgreedMix() {
    List<Transfer> internal = DemoDataCatalog.internalTransfers();

    assertThat(internal).hasSize(43);
    assertThat(countInternal(TransferStatusEnum.COMPLETED)).isEqualTo(36);
    assertThat(countInternal(TransferStatusEnum.REJECTED)).isEqualTo(7);
    Set<String> rejectedIds = internal.stream().filter(t -> t.status() == TransferStatusEnum.REJECTED)
      .map(Transfer::id).collect(Collectors.toSet());
    assertThat(rejectedIds).containsExactlyInAnyOrder("tr3", "demo-tr-006", "demo-tr-012", "demo-tr-018",
      "demo-tr-024", "demo-tr-030", "demo-tr-036");
    Set<String> ibans = accountIbans();
    assertThat(internal).allSatisfy(t -> {
      assertThat(t.source()).isNotEqualTo(t.dest());
      assertThat(ibans).contains(t.source().value(), t.dest().value());
      assertThat(t.money().currency()).isEqualTo("EUR");
    });
  }

  @Test
  void internalSourcesAndDestinationsFollowTheFormula() {
    for (int k = 1; k <= 40; k++) {
      Transfer transfer = internal(String.format("demo-tr-%03d", k));

      assertThat(transfer.source().value()).isEqualTo(newIban(100 + k));
      assertThat(transfer.dest().value()).isEqualTo(newIban(101 + ((k + 6) % 50)));
    }
  }

  @Test
  void internalAmountsFollowTheFormula() {
    assertThat(internal("demo-tr-001").money().amount()).isEqualByComparingTo("89.19");
    assertThat(internal("demo-tr-008").money().amount()).isEqualByComparingTo("643.52");
    assertThat(internal("demo-tr-034").money().amount()).isEqualByComparingTo("32.46");
    assertThat(internal("demo-tr-040").money().amount()).isEqualByComparingTo("507.60");
    assertThat(internal("demo-tr-006").money().amount()).isEqualByComparingTo("10500.00");
    assertThat(internal("demo-tr-036").money().amount()).isEqualByComparingTo("13000.00");
    for (int k = 1; k <= 40; k++) {
      Transfer transfer = internal(String.format("demo-tr-%03d", k));
      if (k % 6 == 0) {
        assertThat(transfer.money().amount()).isEqualByComparingTo(new BigDecimal(10000 + 500 * (k / 6)));
      } else {
        long cents = 1000 + ((long) k * 7919) % 89000;
        assertThat(transfer.money().amount()).isEqualByComparingTo(BigDecimal.valueOf(cents, 2));
      }
    }
  }

  // ------------------------------------------------------------------ external transfers

  @Test
  void has10ExternalTransfersWithTheAgreedStatusesAndReasons() {
    assertThat(DemoDataCatalog.externalTransfers()).hasSize(10);
    for (int k = 1; k <= 4; k++) {
      assertThat(external(k).transfer().status()).isEqualTo(TransferStatusEnum.COMPLETED);
      assertThat(external(k).reason()).isNull();
    }
    assertThat(external(5).transfer().status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(external(5).reason()).isEqualTo("Rejected by the clearing house");
    assertThat(external(6).transfer().status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(external(6).reason()).isEqualTo("Rejected by the clearing house");
    assertThat(external(7).transfer().status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(external(7).reason()).isEqualTo("Clearing house unavailable");
    assertThat(external(8).transfer().status()).isEqualTo(TransferStatusEnum.REJECTED);
    assertThat(external(8).reason()).isEqualTo("Amount must not exceed 10000 EUR");
    assertThat(external(8).transfer().money().amount()).isEqualByComparingTo("12000.00");
    assertThat(external(9).transfer().status()).isEqualTo(TransferStatusEnum.SENT);
    assertThat(external(9).reason()).isNull();
    assertThat(external(10).transfer().status()).isEqualTo(TransferStatusEnum.UNKNOWN);
    assertThat(external(10).reason()).isNull();
  }

  @Test
  void externalAmountsFollowTheFormula() {
    assertThat(external(1).transfer().money().amount()).isEqualByComparingTo("59.73");
    assertThat(external(3).transfer().money().amount()).isEqualByComparingTo("159.19");
    assertThat(external(9).transfer().money().amount()).isEqualByComparingTo("457.57");
    assertThat(external(10).transfer().money().amount()).isEqualByComparingTo("507.30");
    for (int k = 1; k <= 10; k++) {
      if (k != 8) {
        long cents = 1000 + ((long) k * 4973) % 89000;
        assertThat(external(k).transfer().money().amount()).isEqualByComparingTo(BigDecimal.valueOf(cents, 2));
        assertThat(external(k).transfer().money().currency()).isEqualTo("EUR");
      }
    }
  }

  @Test
  void externalDestinationsBelongToOtherBanks() {
    for (int k = 1; k <= 10; k++) {
      Iban dest = external(k).transfer().dest();

      assertThat(dest.bankCode().value()).isEqualTo(OTHER_ABIS[(k - 1) % 4]).isNotEqualTo(OUR_ABI);
      assertThat(dest.value()).isEqualTo("IT60X" + OTHER_ABIS[(k - 1) % 4] + "11101" + fmt12(k));
      assertThat(accountIbans()).doesNotContain(dest.value());
    }
  }

  @Test
  void externalSourcesAreDistinctAccounts141To150() {
    Set<String> sources = new HashSet<>();
    for (int k = 1; k <= 10; k++) {
      String source = external(k).transfer().source().value();

      assertThat(source).isEqualTo(newIban(140 + k));
      sources.add(source);
    }
    assertThat(sources).hasSize(10);
  }

  @Test
  void transferIdsAreUnique() {
    assertThat(allTransfers()).hasSize(53);
    assertThat(allTransfers().stream().map(Transfer::id).collect(Collectors.toSet())).hasSize(53);
  }

  // ------------------------------------------------------------------ coherence of the balances

  @Test
  void legacyDataIsCoherent() {
    assertThat(account("ac1").money().amount()).isEqualByComparingTo("1900.00");
    assertThat(account("ac2").money().amount()).isEqualByComparingTo("2049.75");
    assertThat(account("ac3").money().amount()).isEqualByComparingTo("2050.25");
    Transfer tr1 = internal("tr1");
    assertThat(tr1.source().value()).isEqualTo("IT60X0542811101000000000001");
    assertThat(tr1.dest().value()).isEqualTo("IT60X0542811101000000000002");
    assertThat(tr1.money().amount()).isEqualByComparingTo("100.00");
    assertThat(tr1.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    Transfer tr2 = internal("tr2");
    assertThat(tr2.source().value()).isEqualTo("IT60X0542811101000000000002");
    assertThat(tr2.dest().value()).isEqualTo("IT60X0542811101000000000003");
    assertThat(tr2.money().amount()).isEqualByComparingTo("50.25");
    assertThat(tr2.status()).isEqualTo(TransferStatusEnum.COMPLETED);
    Transfer tr3 = internal("tr3");
    assertThat(tr3.source().value()).isEqualTo("IT60X0542811101000000000003");
    assertThat(tr3.dest().value()).isEqualTo("IT60X0542811101000000000001");
    assertThat(tr3.money().amount()).isEqualByComparingTo("15000.00");
    assertThat(tr3.status()).isEqualTo(TransferStatusEnum.REJECTED);
  }

  @Test
  void balancesAreReplayedIndependentlyFromTheHistory() {
    // every account starts at 2000.00; the movements table is written here, not taken from the catalog
    Map<String, BigDecimal> expected = new HashMap<>();
    DemoDataCatalog.accounts().forEach(a -> expected.put(a.iban().value(), START));

    for (Transfer t : DemoDataCatalog.internalTransfers()) {
      if (t.status() == TransferStatusEnum.COMPLETED) {
        expected.merge(t.source().value(), t.money().amount().negate(), BigDecimal::add);
        expected.merge(t.dest().value(), t.money().amount(), BigDecimal::add);
      }
      // REJECTED: no movement
    }
    for (DemoDataCatalog.ExternalSeed seed : DemoDataCatalog.externalTransfers()) {
      Transfer t = seed.transfer();
      boolean holdsTheMoney = t.status() == TransferStatusEnum.COMPLETED
        || t.status() == TransferStatusEnum.SENT
        || t.status() == TransferStatusEnum.UNKNOWN;
      if (holdsTheMoney) {
        expected.merge(t.source().value(), t.money().amount().negate(), BigDecimal::add);
      }
      // REJECTED: refunded or refused by the fraud check, net zero
    }

    assertThat(expected).hasSize(53);
    for (Account account : DemoDataCatalog.accounts()) {
      assertThat(account.money().amount())
        .as("balance of %s", account.id())
        .isEqualByComparingTo(expected.get(account.iban().value()));
    }
  }

  @Test
  void controlValuesMatch() {
    assertThat(newAccount(101).money().amount()).isEqualByComparingTo("1910.81");
    assertThat(newAccount(108).money().amount()).isEqualByComparingTo("1445.67");
    assertThat(newAccount(141).money().amount()).isEqualByComparingTo("1972.73");
    assertThat(newAccount(143).money().amount()).isEqualByComparingTo("1840.81");
    assertThat(newAccount(147).money().amount()).isEqualByComparingTo("2507.60");
    assertThat(newAccount(148).money().amount()).isEqualByComparingTo("2000.00");
    assertThat(newAccount(149).money().amount()).isEqualByComparingTo("1542.43");
    assertThat(newAccount(150).money().amount()).isEqualByComparingTo("1492.70");
    assertThat(accountByIban(newIban(149)).id()).isEqualTo("demo-acc-149");
  }

  @Test
  void everyAccountIsTheSourceOfAtMostOneMovingTransfer() {
    Map<String, Integer> movingBySource = new HashMap<>();
    for (Transfer t : allTransfers()) {
      boolean moves = t.status() == TransferStatusEnum.COMPLETED
        || t.status() == TransferStatusEnum.SENT
        || t.status() == TransferStatusEnum.UNKNOWN;
      if (moves) {
        movingBySource.merge(t.source().value(), 1, Integer::sum);
        assertThat(t.money().amount()).as("amount of %s", t.id()).isLessThanOrEqualTo(amount("899.99"));
      }
    }

    assertThat(movingBySource.values()).allSatisfy(count -> assertThat(count).isEqualTo(1));
  }

  @Test
  void fraudRejectionsAreAboveTheThreshold() {
    List<Transfer> internalRejected = DemoDataCatalog.internalTransfers().stream()
      .filter(t -> t.status() == TransferStatusEnum.REJECTED).toList();

    assertThat(internalRejected).hasSize(7);
    assertThat(internalRejected)
      .allSatisfy(t -> assertThat(t.money().amount()).isGreaterThan(FRAUD_THRESHOLD));
    assertThat(external(8).transfer().money().amount()).isGreaterThan(FRAUD_THRESHOLD);
  }

  @Test
  void isDeterministic() {
    assertThat(DemoDataCatalog.accounts()).isEqualTo(DemoDataCatalog.accounts());
    assertThat(DemoDataCatalog.internalTransfers()).isEqualTo(DemoDataCatalog.internalTransfers());
    assertThat(DemoDataCatalog.externalTransfers()).isEqualTo(DemoDataCatalog.externalTransfers());
  }
}
