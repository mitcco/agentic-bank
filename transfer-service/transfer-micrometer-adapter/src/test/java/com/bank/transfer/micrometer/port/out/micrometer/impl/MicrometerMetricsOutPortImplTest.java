package com.bank.transfer.micrometer.port.out.micrometer.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.ReconciliationOutcomeEnum;
import com.bank.transfer.application.dto.RefundCauseEnum;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.domain.TransferStatusEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Adapter Micrometer della porta delle metriche (piano transfer-14, D1, D5, D12): contatori registrati a zero
 * all'avvio, gauge che parte da NaN, tag solo con nomi di enum.
 */
class MicrometerMetricsOutPortImplTest {

  private static final String REQUESTS = "bank.transfer.requests";
  private static final String CONCLUDED = "bank.transfer.concluded";
  private static final String REFUNDS = "bank.transfer.refunds";
  private static final String RECONCILIATIONS = "bank.transfer.reconciliations";
  private static final String PUBLISHED = "bank.transfer.outbox.published";
  private static final String PENDING = "bank.transfer.outbox.pending";

  private static final Pattern ENUM_NAME = Pattern.compile("^[A-Z_]+$");
  private static final Pattern IBAN_LIKE = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");
  private static final Pattern ANY_DIGIT = Pattern.compile("\\d");

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final MicrometerMetricsOutPortImpl port = new MicrometerMetricsOutPortImpl(registry);

  // ------------------------------------------------------------------ helpers

  private double requests(TransferTypeEnum type, TransferStatusEnum status) {
    Counter counter = registry.find(REQUESTS).tag("type", type.name()).tag("status", status.name()).counter();
    assertThat(counter).as("counter %s{type=%s,status=%s}", REQUESTS, type, status).isNotNull();
    return counter.count();
  }

  private double concluded(TransferTypeEnum type, TransferStatusEnum status) {
    Counter counter = registry.find(CONCLUDED).tag("type", type.name()).tag("status", status.name()).counter();
    assertThat(counter).as("counter %s{type=%s,status=%s}", CONCLUDED, type, status).isNotNull();
    return counter.count();
  }

  private double refunds(RefundCauseEnum cause) {
    Counter counter = registry.find(REFUNDS).tag("cause", cause.name()).counter();
    assertThat(counter).as("counter %s{cause=%s}", REFUNDS, cause).isNotNull();
    return counter.count();
  }

  private double reconciliations(ReconciliationOutcomeEnum outcome) {
    Counter counter = registry.find(RECONCILIATIONS).tag("outcome", outcome.name()).counter();
    assertThat(counter).as("counter %s{outcome=%s}", RECONCILIATIONS, outcome).isNotNull();
    return counter.count();
  }

  private double published() {
    Counter counter = registry.find(PUBLISHED).counter();
    assertThat(counter).as("counter %s", PUBLISHED).isNotNull();
    return counter.count();
  }

  private double pending() {
    Gauge gauge = registry.find(PENDING).gauge();
    assertThat(gauge).as("gauge %s", PENDING).isNotNull();
    return gauge.value();
  }

  private List<Meter> businessMeters() {
    return registry.getMeters().stream()
      .filter(meter -> meter.getId().getName().startsWith("bank.transfer."))
      .toList();
  }

  private List<Counter> counters() {
    return businessMeters().stream().filter(Counter.class::isInstance).map(Counter.class::cast).toList();
  }

  private double totalOfCounters() {
    return counters().stream().mapToDouble(Counter::count).sum();
  }

  private void callEveryMethodWithEveryValue() {
    for (TransferTypeEnum type : TransferTypeEnum.values()) {
      for (TransferStatusEnum status : TransferStatusEnum.values()) {
        port.transferRequested(type, status);
        port.transferConcluded(type, status);
      }
    }
    for (RefundCauseEnum cause : RefundCauseEnum.values()) {
      port.transferRefunded(cause);
    }
    for (ReconciliationOutcomeEnum outcome : ReconciliationOutcomeEnum.values()) {
      port.reconciliationExamined(outcome);
    }
    port.notificationPublished();
    port.outboxPending(12);
  }

  // ------------------------------------------------------------------ registrazione a zero all'avvio (D12)

  @Test
  void emptyRegistry_afterConstruction_requestsExistAtZeroForTheSixPossibleCombinations() {
    assertThat(registry.find(REQUESTS).counters()).hasSize(6);
    assertThat(requests(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED)).isZero();
    assertThat(requests(TransferTypeEnum.INTERNAL, TransferStatusEnum.REJECTED)).isZero();
    assertThat(requests(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED)).isZero();
    assertThat(requests(TransferTypeEnum.EXTERNAL, TransferStatusEnum.REJECTED)).isZero();
    assertThat(requests(TransferTypeEnum.EXTERNAL, TransferStatusEnum.SENT)).isZero();
    assertThat(requests(TransferTypeEnum.EXTERNAL, TransferStatusEnum.UNKNOWN)).isZero();
  }

  @Test
  void emptyRegistry_afterConstruction_concludedExistsAtZeroForTheFourCombinations() {
    assertThat(registry.find(CONCLUDED).counters()).hasSize(4);
    for (TransferTypeEnum type : TransferTypeEnum.values()) {
      assertThat(concluded(type, TransferStatusEnum.COMPLETED)).isZero();
      assertThat(concluded(type, TransferStatusEnum.REJECTED)).isZero();
    }
  }

  @Test
  void emptyRegistry_afterConstruction_theThreeRefundsAndTheFiveReconciliationsExistAtZero() {
    assertThat(registry.find(REFUNDS).counters()).hasSize(3);
    for (RefundCauseEnum cause : RefundCauseEnum.values()) {
      assertThat(refunds(cause)).isZero();
    }
    assertThat(registry.find(RECONCILIATIONS).counters()).hasSize(5);
    for (ReconciliationOutcomeEnum outcome : ReconciliationOutcomeEnum.values()) {
      assertThat(reconciliations(outcome)).isZero();
    }
  }

  @Test
  void emptyRegistry_afterConstruction_publishedExistsAtZeroAndTheGaugeIsNaN() {
    assertThat(registry.find(PUBLISHED).counters()).hasSize(1);
    assertThat(published()).isZero();
    assertThat(registry.find(PENDING).gauges()).hasSize(1);
    assertThat(pending()).isNaN();
  }

  @Test
  void afterConstruction_theBusinessMetersAreExactlyTheExpectedOnes() {
    assertThat(businessMeters()).hasSize(6 + 4 + 3 + 5 + 1 + 1);
    assertThat(counters()).hasSize(6 + 4 + 3 + 5 + 1);
    assertThat(totalOfCounters()).isZero();
  }

  @Test
  void meterTypes_areCountersExceptThePendingGauge() {
    assertThat(registry.find(REQUESTS).meters()).allMatch(Counter.class::isInstance);
    assertThat(registry.find(CONCLUDED).meters()).allMatch(Counter.class::isInstance);
    assertThat(registry.find(REFUNDS).meters()).allMatch(Counter.class::isInstance);
    assertThat(registry.find(RECONCILIATIONS).meters()).allMatch(Counter.class::isInstance);
    assertThat(registry.find(PUBLISHED).meters()).allMatch(Counter.class::isInstance);
    assertThat(registry.find(PENDING).meters()).allMatch(Gauge.class::isInstance);
  }

  @Test
  void names_haveNoTotalSuffix_thePrometheusRegistryAddsIt() {
    assertThat(businessMeters()).extracting(meter -> meter.getId().getName())
      .containsOnly(REQUESTS, CONCLUDED, REFUNDS, RECONCILIATIONS, PUBLISHED, PENDING)
      .noneMatch(name -> name.endsWith(".total") || name.endsWith("_total"));
  }

  // ------------------------------------------------------------------ ogni metodo: la serie giusta vale 1

  @Test
  void transferRequested_incrementsOnlyTheRightSeries() {
    port.transferRequested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.SENT);

    assertThat(requests(TransferTypeEnum.EXTERNAL, TransferStatusEnum.SENT)).isEqualTo(1.0);
    assertThat(totalOfCounters()).isEqualTo(1.0);
  }

  @Test
  void transferConcluded_incrementsOnlyTheRightSeries() {
    port.transferConcluded(TransferTypeEnum.INTERNAL, TransferStatusEnum.REJECTED);

    assertThat(concluded(TransferTypeEnum.INTERNAL, TransferStatusEnum.REJECTED)).isEqualTo(1.0);
    assertThat(totalOfCounters()).isEqualTo(1.0);
  }

  @Test
  void transferRefunded_incrementsOnlyTheRightSeries() {
    port.transferRefunded(RefundCauseEnum.NOT_FOUND_AT_CLEARING);

    assertThat(refunds(RefundCauseEnum.NOT_FOUND_AT_CLEARING)).isEqualTo(1.0);
    assertThat(refunds(RefundCauseEnum.CLEARING_UNAVAILABLE)).isZero();
    assertThat(refunds(RefundCauseEnum.CLEARING_REJECTED)).isZero();
    assertThat(totalOfCounters()).isEqualTo(1.0);
  }

  @Test
  void reconciliationExamined_incrementsOnlyTheRightSeries() {
    port.reconciliationExamined(ReconciliationOutcomeEnum.SKIPPED);

    assertThat(reconciliations(ReconciliationOutcomeEnum.SKIPPED)).isEqualTo(1.0);
    assertThat(totalOfCounters()).isEqualTo(1.0);
  }

  @Test
  void notificationPublished_incrementsOnlyThePublishedCounter() {
    port.notificationPublished();

    assertThat(published()).isEqualTo(1.0);
    assertThat(totalOfCounters()).isEqualTo(1.0);
    assertThat(pending()).isNaN();
  }

  @Test
  void outboxPending_setsTheGaugeAndTouchesNoCounter() {
    port.outboxPending(5);

    assertThat(pending()).isEqualTo(5.0);
    assertThat(totalOfCounters()).isZero();
  }

  @Test
  void everyReconciliationOutcome_hasItsOwnSeries() {
    for (ReconciliationOutcomeEnum outcome : ReconciliationOutcomeEnum.values()) {
      port.reconciliationExamined(outcome);
    }

    for (ReconciliationOutcomeEnum outcome : ReconciliationOutcomeEnum.values()) {
      assertThat(reconciliations(outcome)).isEqualTo(1.0);
    }
  }

  @Test
  void everyRefundCause_hasItsOwnSeries() {
    for (RefundCauseEnum cause : RefundCauseEnum.values()) {
      port.transferRefunded(cause);
    }

    for (RefundCauseEnum cause : RefundCauseEnum.values()) {
      assertThat(refunds(cause)).isEqualTo(1.0);
    }
  }

  @Test
  void repeatedCalls_accumulate() {
    port.transferRequested(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED);
    port.transferRequested(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED);
    port.transferRequested(TransferTypeEnum.INTERNAL, TransferStatusEnum.REJECTED);
    port.notificationPublished();
    port.notificationPublished();
    port.notificationPublished();

    assertThat(requests(TransferTypeEnum.INTERNAL, TransferStatusEnum.COMPLETED)).isEqualTo(2.0);
    assertThat(requests(TransferTypeEnum.INTERNAL, TransferStatusEnum.REJECTED)).isEqualTo(1.0);
    assertThat(published()).isEqualTo(3.0);
  }

  @Test
  void requestsAndConcludedAreIndependentCounters() {
    port.transferRequested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.SENT);
    port.transferConcluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED);

    assertThat(requests(TransferTypeEnum.EXTERNAL, TransferStatusEnum.SENT)).isEqualTo(1.0);
    assertThat(requests(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED)).isZero();
    assertThat(concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.COMPLETED)).isEqualTo(1.0);
  }

  // ------------------------------------------------------------------ gauge (D4, D5)

  @Test
  void outboxPendingFiveThenTwo_theGaugeHoldsTheLastValue() {
    port.outboxPending(5);
    assertThat(pending()).isEqualTo(5.0);

    port.outboxPending(2);

    assertThat(pending()).isEqualTo(2.0);
  }

  @Test
  void outboxPendingZero_isZeroNotNaN() {
    port.outboxPending(0);

    assertThat(pending()).isZero();
  }

  @Test
  void outboxPendingAfterOtherValues_updatesTheSameSingleGauge() {
    port.outboxPending(7);
    port.outboxPending(0);
    port.outboxPending(3);

    assertThat(registry.find(PENDING).gauges()).hasSize(1);
    assertThat(pending()).isEqualTo(3.0);
  }

  // ------------------------------------------------------------------ combinazione mai vista (D12)

  @Test
  void transferRequestedExternalPending_doesNotThrowAndRegistersANewSeriesAtOne() {
    port.transferRequested(TransferTypeEnum.EXTERNAL, TransferStatusEnum.PENDING);

    assertThat(requests(TransferTypeEnum.EXTERNAL, TransferStatusEnum.PENDING)).isEqualTo(1.0);
    assertThat(registry.find(REQUESTS).counters()).hasSize(7);
  }

  @Test
  void transferRequestedWithAnUnseenCombination_twiceKeepsASingleSeriesAtTwo() {
    port.transferRequested(TransferTypeEnum.INTERNAL, TransferStatusEnum.SENT);
    port.transferRequested(TransferTypeEnum.INTERNAL, TransferStatusEnum.SENT);

    assertThat(registry.find(REQUESTS).tag("type", "INTERNAL").tag("status", "SENT").counters()).hasSize(1);
    assertThat(requests(TransferTypeEnum.INTERNAL, TransferStatusEnum.SENT)).isEqualTo(2.0);
  }

  @Test
  void transferConcludedWithAnUnseenCombination_doesNotThrowAndRegistersTheSeries() {
    port.transferConcluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.UNKNOWN);

    assertThat(concluded(TransferTypeEnum.EXTERNAL, TransferStatusEnum.UNKNOWN)).isEqualTo(1.0);
  }

  // ------------------------------------------------------------------ tag: solo enum, mai dati del bonifico

  @Test
  void anySequence_tagKeysAreOnlyTypeStatusCauseOutcome_andValuesAreEnumNames() {
    callEveryMethodWithEveryValue();

    assertThat(businessMeters()).isNotEmpty();
    for (Meter meter : businessMeters()) {
      for (Tag tag : meter.getId().getTags()) {
        assertThat(tag.getKey()).isIn("type", "status", "cause", "outcome");
        assertThat(tag.getValue()).matches(ENUM_NAME);
        assertThat(IBAN_LIKE.matcher(tag.getValue()).find()).as("IBAN-like tag value %s", tag.getValue()).isFalse();
        assertThat(ANY_DIGIT.matcher(tag.getValue()).find()).as("tag value with digits %s", tag.getValue())
          .isFalse();
      }
    }
  }

  @Test
  void theCountersWithoutTags_arePublishedAndPending() {
    callEveryMethodWithEveryValue();

    assertThat(businessMeters().stream().filter(meter -> meter.getId().getTags().isEmpty()))
      .extracting(meter -> meter.getId().getName())
      .containsExactlyInAnyOrder(PUBLISHED, PENDING);
  }

  @Test
  void tagKeysPerMeter_matchTheDocumentedOnes() {
    callEveryMethodWithEveryValue();

    assertThat(registry.find(REQUESTS).meters()).allSatisfy(meter ->
      assertThat(meter.getId().getTags()).extracting(Tag::getKey).containsExactlyInAnyOrder("type", "status"));
    assertThat(registry.find(CONCLUDED).meters()).allSatisfy(meter ->
      assertThat(meter.getId().getTags()).extracting(Tag::getKey).containsExactlyInAnyOrder("type", "status"));
    assertThat(registry.find(REFUNDS).meters()).allSatisfy(meter ->
      assertThat(meter.getId().getTags()).extracting(Tag::getKey).containsExactly("cause"));
    assertThat(registry.find(RECONCILIATIONS).meters()).allSatisfy(meter ->
      assertThat(meter.getId().getTags()).extracting(Tag::getKey).containsExactly("outcome"));
  }

  @Test
  void allEnumValuesUsed_theTagValuesAreSubsetsOfTheEnumNames() {
    callEveryMethodWithEveryValue();

    List<String> causes = java.util.Arrays.stream(RefundCauseEnum.values()).map(Enum::name).toList();
    List<String> outcomes = java.util.Arrays.stream(ReconciliationOutcomeEnum.values()).map(Enum::name).toList();
    assertThat(registry.find(REFUNDS).counters()).extracting(c -> c.getId().getTag("cause"))
      .containsExactlyInAnyOrderElementsOf(causes);
    assertThat(registry.find(RECONCILIATIONS).counters()).extracting(c -> c.getId().getTag("outcome"))
      .containsExactlyInAnyOrderElementsOf(outcomes);
  }

  // ------------------------------------------------------------------ null

  @Test
  void nullRegistry_throwsNullPointerException() {
    assertThatThrownBy(() -> new MicrometerMetricsOutPortImpl(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void transferRequestedWithNullType_throwsNullPointerExceptionAndChangesNothing() {
    assertThatThrownBy(() -> port.transferRequested(null, TransferStatusEnum.COMPLETED))
      .isInstanceOf(NullPointerException.class);

    assertThat(totalOfCounters()).isZero();
  }

  @Test
  void transferRequestedWithNullStatus_throwsNullPointerExceptionAndChangesNothing() {
    assertThatThrownBy(() -> port.transferRequested(TransferTypeEnum.INTERNAL, null))
      .isInstanceOf(NullPointerException.class);

    assertThat(totalOfCounters()).isZero();
  }

  @Test
  void transferConcludedWithNullType_throwsNullPointerExceptionAndChangesNothing() {
    assertThatThrownBy(() -> port.transferConcluded(null, TransferStatusEnum.COMPLETED))
      .isInstanceOf(NullPointerException.class);

    assertThat(totalOfCounters()).isZero();
  }

  @Test
  void transferConcludedWithNullStatus_throwsNullPointerExceptionAndChangesNothing() {
    assertThatThrownBy(() -> port.transferConcluded(TransferTypeEnum.INTERNAL, null))
      .isInstanceOf(NullPointerException.class);

    assertThat(totalOfCounters()).isZero();
  }

  @Test
  void transferRefundedWithNullCause_throwsNullPointerExceptionAndChangesNothing() {
    assertThatThrownBy(() -> port.transferRefunded(null)).isInstanceOf(NullPointerException.class);

    assertThat(totalOfCounters()).isZero();
  }

  @Test
  void reconciliationExaminedWithNullOutcome_throwsNullPointerExceptionAndChangesNothing() {
    assertThatThrownBy(() -> port.reconciliationExamined(null)).isInstanceOf(NullPointerException.class);

    assertThat(totalOfCounters()).isZero();
  }
}
