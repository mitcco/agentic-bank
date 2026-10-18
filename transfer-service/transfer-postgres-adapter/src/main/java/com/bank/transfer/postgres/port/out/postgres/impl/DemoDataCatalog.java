package com.bank.transfer.postgres.port.out.postgres.impl;

import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The demo data, as pure and deterministic data. Every account starts at {@link #INITIAL_BALANCE}; its balance is
 * then computed by replaying the demo transfers: an internal COMPLETED transfer moves the money from the source to
 * the destination, an external COMPLETED, SENT or UNKNOWN transfer only debits the source, a REJECTED transfer
 * moves nothing (refunded or refused before any debit).
 */
final class DemoDataCatalog {

  static final BigDecimal INITIAL_BALANCE = new BigDecimal("2000.00");

  static final String REASON_REJECTED_BY_CLEARING = "Rejected by the clearing house";
  static final String REASON_CLEARING_UNAVAILABLE = "Clearing house unavailable";
  static final String REASON_FRAUD_THRESHOLD = "Amount must not exceed 10000 EUR";

  private static final String CURRENCY = "EUR";
  private static final String[] OTHER_ABIS = {"03069", "02008", "05034", "01030"};

  /**
   * An external transfer together with the reason of its outcome, which may be null.
   */
  record ExternalSeed(Transfer transfer, String reason) {
  }

  private DemoDataCatalog() {
  }

  static List<Account> accounts() {
    Map<String, BigDecimal> balances = new HashMap<>();
    List<String[]> idsAndIbans = new ArrayList<>();
    for (int n = 1; n <= 3; n++) {
      idsAndIbans.add(new String[] {"ac" + n, ourIban(n)});
    }
    for (int n = 101; n <= 150; n++) {
      idsAndIbans.add(new String[] {"demo-acc-" + n, ourIban(n)});
    }
    idsAndIbans.forEach(pair -> balances.put(pair[1], INITIAL_BALANCE));

    for (Transfer transfer : internalTransfers()) {
      if (transfer.status() == TransferStatusEnum.COMPLETED) {
        balances.merge(transfer.source().value(), transfer.money().amount().negate(), BigDecimal::add);
        balances.merge(transfer.dest().value(), transfer.money().amount(), BigDecimal::add);
      }
    }
    for (ExternalSeed seed : externalTransfers()) {
      Transfer transfer = seed.transfer();
      if (transfer.status() != TransferStatusEnum.REJECTED) {
        balances.merge(transfer.source().value(), transfer.money().amount().negate(), BigDecimal::add);
      }
    }

    List<Account> accounts = new ArrayList<>();
    for (String[] pair : idsAndIbans) {
      accounts.add(new Account(pair[0], new Iban(pair[1]), eur(balances.get(pair[1]))));
    }
    return List.copyOf(accounts);
  }

  static List<Transfer> internalTransfers() {
    List<Transfer> transfers = new ArrayList<>();
    transfers.add(transfer("tr1", ourIban(1), ourIban(2), cents(10000), TransferStatusEnum.COMPLETED));
    transfers.add(transfer("tr2", ourIban(2), ourIban(3), cents(5025), TransferStatusEnum.COMPLETED));
    transfers.add(transfer("tr3", ourIban(3), ourIban(1), cents(1500000), TransferStatusEnum.REJECTED));
    for (int k = 1; k <= 40; k++) {
      boolean rejected = k % 6 == 0;
      long cents = rejected ? 1000000 + 50000L * (k / 6) : 1000 + ((long) k * 7919) % 89000;
      transfers.add(transfer(String.format("demo-tr-%03d", k), ourIban(100 + k), ourIban(101 + ((k + 6) % 50)),
        cents(cents), rejected ? TransferStatusEnum.REJECTED : TransferStatusEnum.COMPLETED));
    }
    return List.copyOf(transfers);
  }

  static List<ExternalSeed> externalTransfers() {
    List<ExternalSeed> seeds = new ArrayList<>();
    for (int k = 1; k <= 10; k++) {
      long cents = k == 8 ? 1200000 : 1000 + ((long) k * 4973) % 89000;
      String dest = "IT60X" + OTHER_ABIS[(k - 1) % 4] + "11101" + String.format("%012d", k);
      TransferStatusEnum status = switch (k) {
        case 1, 2, 3, 4 -> TransferStatusEnum.COMPLETED;
        case 9 -> TransferStatusEnum.SENT;
        case 10 -> TransferStatusEnum.UNKNOWN;
        default -> TransferStatusEnum.REJECTED;
      };
      String reason = switch (k) {
        case 5, 6 -> REASON_REJECTED_BY_CLEARING;
        case 7 -> REASON_CLEARING_UNAVAILABLE;
        case 8 -> REASON_FRAUD_THRESHOLD;
        default -> null;
      };
      seeds.add(new ExternalSeed(
        transfer(String.format("demo-ext-%03d", k), ourIban(140 + k), dest, cents(cents), status), reason));
    }
    return List.copyOf(seeds);
  }

  private static String ourIban(int accountNumber) {
    return "IT60X0542811101" + String.format("%012d", accountNumber);
  }

  private static BigDecimal cents(long cents) {
    return BigDecimal.valueOf(cents, 2);
  }

  private static Money eur(BigDecimal amount) {
    return new Money(amount, CURRENCY);
  }

  private static Transfer transfer(String id, String source, String dest, BigDecimal amount,
    TransferStatusEnum status) {
    return new Transfer(id, new Iban(source), new Iban(dest), eur(amount), status);
  }
}
