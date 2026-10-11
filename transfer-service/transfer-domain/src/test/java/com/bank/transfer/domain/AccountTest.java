package com.bank.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.exception.DifferentCurrencyException;
import com.bank.transfer.domain.exception.InsufficientFundException;
import java.math.BigDecimal;
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
  void chargeAndCreditKeepIdAndIban() {
    Account account = account("100");

    assertThat(account.charge(eur("10")).id()).isEqualTo("acc-1");
    assertThat(account.charge(eur("10")).iban()).isEqualTo(IBAN);
    assertThat(account.credit(eur("10")).id()).isEqualTo("acc-1");
    assertThat(account.credit(eur("10")).iban()).isEqualTo(IBAN);
  }

  @Test
  void differentCurrencyIsRejected() {
    Money usd = new Money(BigDecimal.ONE, "USD");

    assertThatThrownBy(() -> account("100").charge(usd))
        .isInstanceOf(DifferentCurrencyException.class);
    assertThatThrownBy(() -> account("100").credit(usd))
        .isInstanceOf(DifferentCurrencyException.class);
  }

  @Test
  void rejectsNullFields() {
    assertThatThrownBy(() -> new Account(null, IBAN, eur("1")))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Account("acc-1", null, eur("1")))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Account("acc-1", IBAN, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void hasIbanMatchesRegardlessOfFormatting() {
    assertThat(account("100").hasIban(new Iban("it60 x054 2811 1010 0000 0123 456"))).isTrue();
  }

  @Test
  void hasIbanIsFalseForForeignIban() {
    assertThat(account("100").hasIban(new Iban("IT60X0542811101000000999999"))).isFalse();
  }
}
