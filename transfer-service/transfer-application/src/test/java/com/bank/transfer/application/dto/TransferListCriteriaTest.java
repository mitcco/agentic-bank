package com.bank.transfer.application.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.TransferStatusEnum;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransferListCriteriaTest {

  private static final String IBAN_VALUE = "IT60X0542811101000000000141";
  private static final Iban IBAN = new Iban(IBAN_VALUE);
  private static final Set<TransferStatusEnum> STATUSES = EnumSet.of(TransferStatusEnum.COMPLETED);
  private static final Set<TransferTypeEnum> TYPES = EnumSet.allOf(TransferTypeEnum.class);
  private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-10-19T00:00:00Z");

  private static TransferListCriteria criteriaWithNull(String field) {
    return new TransferListCriteria(
      "iban".equals(field) ? null : IBAN,
      "direction".equals(field) ? null : TransferDirectionEnum.ALL,
      "statuses".equals(field) ? null : STATUSES,
      "types".equals(field) ? null : TYPES,
      FROM, TO, 20, 10);
  }

  @Test
  void keepsAllTheFields() {
    TransferListCriteria criteria = new TransferListCriteria(IBAN, TransferDirectionEnum.OUTGOING, STATUSES, TYPES,
      FROM, TO, 20, 10);

    assertThat(criteria.iban()).isEqualTo(IBAN);
    assertThat(criteria.direction()).isEqualTo(TransferDirectionEnum.OUTGOING);
    assertThat(criteria.statuses()).containsExactly(TransferStatusEnum.COMPLETED);
    assertThat(criteria.types()).containsExactlyInAnyOrder(TransferTypeEnum.INTERNAL, TransferTypeEnum.EXTERNAL);
    assertThat(criteria.from()).isEqualTo(FROM);
    assertThat(criteria.to()).isEqualTo(TO);
    assertThat(criteria.offset()).isEqualTo(20);
    assertThat(criteria.size()).isEqualTo(10);
  }

  @ParameterizedTest
  @ValueSource(strings = {"iban", "direction", "statuses", "types"})
  void aMissingMandatoryFieldIsRefusedNamingTheField(String field) {
    assertThatThrownBy(() -> criteriaWithNull(field))
      .isInstanceOf(NullPointerException.class)
      .hasMessageContaining(field);
  }

  @Test
  void theDateBoundsAreOptional() {
    TransferListCriteria criteria = new TransferListCriteria(IBAN, TransferDirectionEnum.ALL, Set.of(), TYPES,
      null, null, 0, 20);

    assertThat(criteria.from()).isNull();
    assertThat(criteria.to()).isNull();
    assertThat(criteria.statuses()).isEmpty();
  }

  @Test
  void toStringNeverShowsTheIban() {
    String text = criteriaWithNull("none").toString();

    assertThat(text).doesNotContain(IBAN_VALUE).doesNotContain("0542811101");
  }
}
