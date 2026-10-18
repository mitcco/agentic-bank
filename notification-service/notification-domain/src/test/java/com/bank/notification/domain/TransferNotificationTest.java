package com.bank.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.notification.domain.exception.InvalidNotificationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class TransferNotificationTest {

  private static final String ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String DESTINATION = "IT60X0542811101000000000002";
  private static final Instant AT = Instant.parse("2026-10-13T09:30:00Z");

  private static Money money(String amount) {
    return new Money(new BigDecimal(amount), "EUR");
  }

  private static TransferNotification completed() {
    return build(ID, new Iban(SOURCE), new Iban(DESTINATION), money("100.25"),
        TransferOutcome.COMPLETED, List.of(), AT);
  }

  private static TransferNotification rejected(List<String> reasons) {
    return build(ID, new Iban(SOURCE), new Iban(DESTINATION), money("20000.00"),
        TransferOutcome.REJECTED, reasons, AT);
  }

  private static TransferNotification build(String transferId, Iban source, Iban destination,
      Money amount, TransferOutcome outcome, List<String> reasons, Instant occurredAt) {
    return new TransferNotification(transferId, source, destination, amount, outcome, reasons,
        occurredAt);
  }

  @Test
  void buildsACompletedNotificationExposingAllValues() {
    TransferNotification n = completed();

    assertThat(n.transferId()).isEqualTo(ID);
    assertThat(n.sourceIban().value()).isEqualTo(SOURCE);
    assertThat(n.destinationIban().value()).isEqualTo(DESTINATION);
    assertThat(n.amount().amount()).isEqualByComparingTo(new BigDecimal("100.25"));
    assertThat(n.amount().currency()).isEqualTo("EUR");
    assertThat(n.outcome()).isEqualTo(TransferOutcome.COMPLETED);
    assertThat(n.reasons()).isEmpty();
    assertThat(n.occurredAt()).isEqualTo(AT);
  }

  @Test
  void buildsARejectedNotificationKeepingReasonsInOrder() {
    TransferNotification n =
        rejected(List.of("Amount must not exceed 10000 EUR", "Source IBAN is locked"));

    assertThat(n.outcome()).isEqualTo(TransferOutcome.REJECTED);
    assertThat(n.reasons())
        .containsExactly("Amount must not exceed 10000 EUR", "Source IBAN is locked");
  }

  @Test
  void keepsReasonsExactlyAsReceivedWithoutTrim() {
    assertThat(rejected(List.of("  Source IBAN is locked ")).reasons())
        .containsExactly("  Source IBAN is locked ");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void rejectsNullOrBlankTransferId(String transferId) {
    assertThatThrownBy(() -> build(transferId, new Iban(SOURCE), new Iban(DESTINATION),
        money("100.25"), TransferOutcome.COMPLETED, List.of(), AT))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("transferId");
  }

  @Test
  void acceptsATransferIdThatIsNotAUuid() {
    assertThat(build("abc", new Iban(SOURCE), new Iban(DESTINATION), money("100.25"),
        TransferOutcome.COMPLETED, List.of(), AT).transferId()).isEqualTo("abc");
  }

  @Test
  void rejectsNullSourceIbanNamingTheField() {
    assertThatThrownBy(() -> build(ID, null, new Iban(DESTINATION), money("100.25"),
        TransferOutcome.COMPLETED, List.of(), AT))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("source");
  }

  @Test
  void rejectsNullDestinationIbanNamingTheField() {
    assertThatThrownBy(() -> build(ID, new Iban(SOURCE), null, money("100.25"),
        TransferOutcome.COMPLETED, List.of(), AT))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("destination");
  }

  @Test
  void rejectsNullAmountNamingTheField() {
    assertThatThrownBy(() -> build(ID, new Iban(SOURCE), new Iban(DESTINATION), null,
        TransferOutcome.COMPLETED, List.of(), AT))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("amount");
  }

  @Test
  void rejectsNullOutcomeNamingTheField() {
    assertThatThrownBy(() -> build(ID, new Iban(SOURCE), new Iban(DESTINATION), money("100.25"),
        null, List.of(), AT))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("outcome");
  }

  @Test
  void rejectsNullOccurredAtNamingTheField() {
    assertThatThrownBy(() -> build(ID, new Iban(SOURCE), new Iban(DESTINATION), money("100.25"),
        TransferOutcome.COMPLETED, List.of(), null))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("occurredAt");
  }

  @Test
  void acceptsOccurredAtInTheFuture() {
    Instant future = Instant.parse("2999-01-01T00:00:00Z");

    assertThat(build(ID, new Iban(SOURCE), new Iban(DESTINATION), money("100.25"),
        TransferOutcome.COMPLETED, List.of(), future).occurredAt()).isEqualTo(future);
  }

  @Test
  void rejectsRejectedNotificationWithoutReasons() {
    assertThatThrownBy(() -> rejected(List.of()))
        .isInstanceOf(InvalidNotificationException.class);
  }

  @Test
  void rejectsRejectedNotificationWithBlankReason() {
    assertThatThrownBy(() -> rejected(List.of("motivo", "")))
        .isInstanceOf(InvalidNotificationException.class);
    assertThatThrownBy(() -> rejected(List.of("   ")))
        .isInstanceOf(InvalidNotificationException.class);
  }

  @Test
  void rejectsNullReasonElementWithDomainExceptionNotNpe() {
    assertThatThrownBy(() -> rejected(Arrays.asList("a", null)))
        .isInstanceOf(InvalidNotificationException.class);
  }

  @Test
  void rejectsNullReasonsListForBothOutcomes() {
    assertThatThrownBy(() -> build(ID, new Iban(SOURCE), new Iban(DESTINATION), money("100.25"),
        TransferOutcome.COMPLETED, null, AT))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("reasons");
    assertThatThrownBy(() -> rejected(null))
        .isInstanceOf(InvalidNotificationException.class)
        .hasMessageContaining("reasons");
  }

  @Test
  void rejectsCompletedNotificationWithReasons() {
    assertThatThrownBy(() -> build(ID, new Iban(SOURCE), new Iban(DESTINATION), money("100.25"),
        TransferOutcome.COMPLETED, List.of("motivo"), AT))
        .isInstanceOf(InvalidNotificationException.class);
  }

  @Test
  void isNotAffectedByLaterChangesToTheOriginalReasonsList() {
    List<String> original = new ArrayList<>(List.of("motivo"));
    TransferNotification n = rejected(original);

    original.add("altro");
    original.set(0, "cambiato");

    assertThat(n.reasons()).containsExactly("motivo");
  }

  @Test
  void exposesReasonsAsImmutableList() {
    TransferNotification n = rejected(List.of("motivo"));

    assertThatThrownBy(() -> n.reasons().add("altro"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equalityIgnoresAmountScale() {
    TransferNotification a = build(ID, new Iban(SOURCE), new Iban(DESTINATION), money("100.25"),
        TransferOutcome.COMPLETED, List.of(), AT);
    TransferNotification b = build(ID, new Iban(SOURCE), new Iban(DESTINATION), money("100.2500"),
        TransferOutcome.COMPLETED, List.of(), AT);

    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
  }

  @Test
  void toStringContainsTransferIdButNoIban() {
    List<TransferNotification> all =
        List.of(completed(), rejected(List.of("Source IBAN is locked")));

    for (TransferNotification n : all) {
      assertThat(n.toString())
          .contains(ID)
          .doesNotContain(SOURCE)
          .doesNotContain(DESTINATION)
          .doesNotContain("0542811101");
    }
  }

  @Test
  void errorMessagesContainNeitherIbansNorAmount() {
    Iban source = new Iban(SOURCE);
    Iban destination = new Iban(DESTINATION);
    Money amount = money("1234.56");
    List<Runnable> failing = List.of(
        () -> build(ID, source, destination, amount, TransferOutcome.REJECTED, List.of(), AT),
        () -> build(ID, source, destination, amount, TransferOutcome.COMPLETED,
            List.of("motivo"), AT),
        () -> build("", source, destination, amount, TransferOutcome.COMPLETED, List.of(), AT),
        () -> build(ID, source, destination, amount, TransferOutcome.REJECTED, null, AT),
        () -> build(ID, source, destination, amount, TransferOutcome.REJECTED, List.of(" "), AT),
        () -> build(ID, source, destination, amount, TransferOutcome.REJECTED,
            Arrays.asList("a", null), AT),
        () -> build(ID, source, destination, amount, null, List.of(), AT),
        () -> build(ID, source, destination, amount, TransferOutcome.COMPLETED, List.of(), null),
        () -> build(ID, null, destination, amount, TransferOutcome.COMPLETED, List.of(), AT));

    for (Runnable construction : failing) {
      assertThatThrownBy(construction::run)
          .isInstanceOf(InvalidNotificationException.class)
          .message()
          .doesNotContain(SOURCE)
          .doesNotContain(DESTINATION)
          .doesNotContain("0542811101")
          .doesNotContain("1234.56");
    }
  }

  @Test
  void acceptsSourceEqualToDestination() {
    assertThatCode(() -> build(ID, new Iban(SOURCE), new Iban(SOURCE), money("100.25"),
        TransferOutcome.COMPLETED, List.of(), AT)).doesNotThrowAnyException();
  }
}
