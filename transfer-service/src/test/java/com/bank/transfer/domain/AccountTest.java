package com.bank.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.exception.DifferentCurrencyException;
import com.bank.transfer.domain.exception.InsufficientFundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountTest {

  private static final Iban IBAN = new Iban("IT60X0542811101000000123456");

  private static Money eur(String amount) {
    return new Money(new BigDecimal(amount), "EUR");
  }

  private static Account account(String balance) {
    return new Account("acc-1", IBAN, eur(balance));
  }

  @Test
  void chargeReturnsNewAccountWithReducedBalance() {
    Account original = account("100");

    Account charged = original.charge(eur("30"));

    assertThat(charged.money()).isEqualTo(eur("70"));
    assertThat(original.money()).isEqualTo(eur("100"));
  }

  @Test
  void chargeOfEntireBalanceIsAllowed() {
    assertThat(account("100").charge(eur("100.00")).money()).isEqualTo(eur("0"));
  }

  @Test
  void chargeAboveBalanceFails() {
    assertThatThrownBy(() -> account("10").charge(eur("10.01")))
        .isInstanceOf(InsufficientFundException.class);
  }

  @Test
  void creditReturnsNewAccountWithIncreasedBalance() {
    Account original = account("100");

    Account credited = original.credit(eur("25.50"));

    assertThat(credited.money()).isEqualTo(eur("125.50"));
    assertThat(original.money()).isEqualTo(eur("100"));
  }

  @Test
  void canHaveMultipleIbans() {
    Iban other = new Iban("IT60X0542811101000000654321");

    Account account = new Account("acc-1", List.of(IBAN, other), eur("100"));

    assertThat(account.ibans()).containsExactly(IBAN, other);
  }

  @Test
  void singleIbanConstructorCreatesAccountWithOneIban() {
    assertThat(account("100").ibans()).containsExactly(IBAN);
  }

  @Test
  void ibansAreDefensivelyCopiedAndImmutable() {
    List<Iban> source = new ArrayList<>(List.of(IBAN));
    Account account = new Account("acc-1", source, eur("100"));

    source.add(new Iban("IT60X0542811101000000654321"));

    assertThat(account.ibans()).containsExactly(IBAN);
    assertThatThrownBy(() -> account.ibans().add(IBAN))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void rejectsEmptyIbans() {
    assertThatThrownBy(() -> new Account("acc-1", List.of(), eur("100")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsDuplicateIbansEvenIfFormattedDifferently() {
    Iban spaced = new Iban("it60 x054 2811 1010 0000 0123 456");

    assertThatThrownBy(() -> new Account("acc-1", List.of(IBAN, spaced), eur("100")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullIbans() {
    assertThatThrownBy(() -> new Account("acc-1", (List<Iban>) null, eur("100")))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void chargeAndCreditKeepAllIbans() {
    Iban other = new Iban("IT60X0542811101000000654321");
    Account account = new Account("acc-1", List.of(IBAN, other), eur("100"));

    assertThat(account.charge(eur("10")).ibans()).containsExactly(IBAN, other);
    assertThat(account.credit(eur("10")).ibans()).containsExactly(IBAN, other);
  }

  @Test
  void withIbanReturnsNewAccountWithAddedIban() {
    Iban other = new Iban("IT60X0542811101000000654321");
    Account original = account("100");

    Account updated = original.addIban(other);

    assertThat(updated.ibans()).containsExactly(IBAN, other);
    assertThat(original.ibans()).containsExactly(IBAN);
    assertThat(updated.id()).isEqualTo(original.id());
    assertThat(updated.money()).isEqualTo(original.money());
  }

  @Test
  void withIbanRejectsDuplicate() {
    Iban spaced = new Iban("it60 x054 2811 1010 0000 0123 456");

    assertThatThrownBy(() -> account("100").addIban(spaced))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withIbanRejectsNull() {
    assertThatThrownBy(() -> account("100").addIban(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void hasIbanFindsOwnedIbanRegardlessOfFormatting() {
    Iban other = new Iban("IT60X0542811101000000654321");
    Account account = new Account("acc-1", List.of(IBAN, other), eur("100"));

    assertThat(account.hasIban(other)).isTrue();
    assertThat(account.hasIban(new Iban("it60 x054 2811 1010 0000 0123 456"))).isTrue();
  }

  @Test
  void hasIbanIsFalseForForeignIban() {
    assertThat(account("100").hasIban(new Iban("IT60X0542811101000000999999"))).isFalse();
  }

  @Test
  void differentCurrencyIsRejected() {
    Money usd = new Money(BigDecimal.ONE, "USD");

    assertThatThrownBy(() -> account("100").charge(usd))
        .isInstanceOf(DifferentCurrencyException.class);
    assertThatThrownBy(() -> account("100").credit(usd))
        .isInstanceOf(DifferentCurrencyException.class);
  }
}
