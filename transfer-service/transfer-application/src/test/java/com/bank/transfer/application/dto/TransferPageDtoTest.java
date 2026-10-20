package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferPageDtoTest {

  private static final String COUNTERPARTY_MASKED = "IT60" + "*".repeat(19) + "0001";

  private static TransferListItemDto item(String id) {
    return new TransferListItemDto(id, "INTERNAL", "INCOMING", COUNTERPARTY_MASKED, new BigDecimal("100.25"),
      "EUR", "COMPLETED", Instant.parse("2026-10-19T08:30:00Z"));
  }

  @Test
  void keepsAllTheFields() {
    TransferPageDto page = new TransferPageDto(List.of(item("t-1"), item("t-2")), 2, 10, 31, true);

    assertThat(page.items()).extracting(TransferListItemDto::transferId).containsExactly("t-1", "t-2");
    assertThat(page.page()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(10);
    assertThat(page.totalElements()).isEqualTo(31L);
    assertThat(page.hasNext()).isTrue();
  }

  @Test
  void aMissingItemsListIsRefusedNamingTheField() {
    assertThatThrownBy(() -> new TransferPageDto(null, 0, 20, 0, false))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining("items");
  }

  @Test
  void anEmptyPageIsAllowed() {
    TransferPageDto page = new TransferPageDto(List.of(), 0, 20, 0, false);

    assertThat(page.items()).isEmpty();
    assertThat(page.hasNext()).isFalse();
  }

  @Test
  void toStringNeverShowsCounterpartiesNorAmounts() {
    String text = new TransferPageDto(List.of(item("t-1")), 0, 20, 1, false).toString();

    assertThat(text)
      .doesNotContain(COUNTERPARTY_MASKED)
      .doesNotContain("0001")
      .doesNotContain("100.25");
  }
}
