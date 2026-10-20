package com.bank.transfer.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ListTransfersCommandTest {

  private static final String IBAN = "IT60X0542811101000000000141";

  private static ListTransfersCommand commandWith(List<String> statuses) {
    return new ListTransfersCommand(IBAN, "OUTGOING", statuses, "INTERNAL", "2026-10-01T00:00:00Z",
      "2026-10-19T00:00:00Z", "3", "50");
  }

  @Test
  void toStringShowsNeitherTheIbanNorAnyOtherValue() {
    String text = commandWith(List.of("COMPLETED")).toString();

    assertThat(text)
      .startsWith("ListTransfersCommand")
      .doesNotContain(IBAN)
      .doesNotContain("0542811101")
      .doesNotContain("COMPLETED")
      .doesNotContain("OUTGOING")
      .doesNotContain("INTERNAL")
      .doesNotContain("2026-10-19T00:00:00Z")
      .doesNotContain("2026-10-01T00:00:00Z")
      .doesNotContain("iban=")
      .doesNotContain("page=")
      .doesNotContain("size=");
  }

  @Test
  void toStringDoesNotFailWithAllFieldsNull() {
    assertThatCode(() -> new ListTransfersCommand(null, null, null, null, null, null, null, null).toString())
      .doesNotThrowAnyException();
  }

  @Test
  void changingTheOriginalListAfterConstructionDoesNotChangeTheCommand() {
    List<String> original = new ArrayList<>(List.of("COMPLETED"));
    ListTransfersCommand command = commandWith(original);

    original.add("REJECTED");
    original.set(0, "SENT");

    assertThat(command.statuses()).containsExactly("COMPLETED");
  }

  @Test
  void theStatusesListIsImmutable() {
    ListTransfersCommand command = commandWith(new ArrayList<>(List.of("COMPLETED")));

    assertThatThrownBy(() -> command.statuses().add("REJECTED"))
      .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> command.statuses().clear())
      .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aNullStatusesListBecomesAnEmptyImmutableList() {
    ListTransfersCommand command = commandWith(null);

    assertThat(command.statuses()).isEmpty();
    assertThatThrownBy(() -> command.statuses().add("COMPLETED"))
      .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nullElementsInTheStatusesAreToleratedAndKept() {
    List<String> withNull = Arrays.asList("COMPLETED", null);

    ListTransfersCommand command = commandWith(withNull);

    assertThat(command.statuses()).containsExactly("COMPLETED", null);
  }

  @Test
  void twoCommandsWithTheSameFieldsAreEqualWithTheSameHashCode() {
    ListTransfersCommand first = commandWith(new ArrayList<>(List.of("COMPLETED", "SENT")));
    ListTransfersCommand second = commandWith(new ArrayList<>(List.of("COMPLETED", "SENT")));

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  void twoCommandsWithDifferentStatusesAreNotEqual() {
    assertThat(commandWith(List.of("COMPLETED"))).isNotEqualTo(commandWith(List.of("REJECTED")));
  }
}
