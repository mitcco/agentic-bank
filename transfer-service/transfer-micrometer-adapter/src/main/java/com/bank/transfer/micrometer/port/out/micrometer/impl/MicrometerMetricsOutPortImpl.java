package com.bank.transfer.micrometer.port.out.micrometer.impl;

import com.bank.transfer.application.dto.ReconciliationOutcomeEnum;
import com.bank.transfer.application.dto.RefundCauseEnum;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.port.out.MetricsOutPort;
import com.bank.transfer.domain.TransferStatusEnum;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer adapter of the business metrics. Tags carry only enum names: never IBANs, amounts or ids.
 * Combinations not registered at start are registered at first use; the cardinality stays bounded by the enums.
 */
public class MicrometerMetricsOutPortImpl implements MetricsOutPort {

  private static final String REQUESTS = "bank.transfer.requests";
  private static final String CONCLUDED = "bank.transfer.concluded";
  private static final String REFUNDS = "bank.transfer.refunds";
  private static final String RECONCILIATIONS = "bank.transfer.reconciliations";
  private static final String PUBLISHED = "bank.transfer.outbox.published";
  private static final String PENDING = "bank.transfer.outbox.pending";

  private static final List<TransferStatusEnum> EXTERNAL_REQUEST_STATUSES = List.of(TransferStatusEnum.COMPLETED,
    TransferStatusEnum.REJECTED, TransferStatusEnum.SENT, TransferStatusEnum.UNKNOWN);
  private static final List<TransferStatusEnum> FINAL_STATUSES =
    List.of(TransferStatusEnum.COMPLETED, TransferStatusEnum.REJECTED);

  private final MeterRegistry registry;
  private final Map<TransferTypeEnum, Map<TransferStatusEnum, Counter>> requests = new ConcurrentHashMap<>();
  private final Map<TransferTypeEnum, Map<TransferStatusEnum, Counter>> concluded = new ConcurrentHashMap<>();
  private final Map<RefundCauseEnum, Counter> refunds = new EnumMap<>(RefundCauseEnum.class);
  private final Map<ReconciliationOutcomeEnum, Counter> reconciliations =
    new EnumMap<>(ReconciliationOutcomeEnum.class);
  private final Counter published;
  private final AtomicLong pending = new AtomicLong();
  private volatile boolean pendingKnown;

  public MicrometerMetricsOutPortImpl(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
    for (TransferStatusEnum status : FINAL_STATUSES) {
      requestCounter(TransferTypeEnum.INTERNAL, status);
    }
    for (TransferStatusEnum status : EXTERNAL_REQUEST_STATUSES) {
      requestCounter(TransferTypeEnum.EXTERNAL, status);
    }
    for (TransferTypeEnum type : TransferTypeEnum.values()) {
      for (TransferStatusEnum status : FINAL_STATUSES) {
        concludedCounter(type, status);
      }
    }
    for (RefundCauseEnum cause : RefundCauseEnum.values()) {
      refunds.put(cause, Counter.builder(REFUNDS)
        .description("Refunds of external transfers already debited, by cause")
        .tag("cause", cause.name())
        .register(registry));
    }
    for (ReconciliationOutcomeEnum outcome : ReconciliationOutcomeEnum.values()) {
      reconciliations.put(outcome, Counter.builder(RECONCILIATIONS)
        .description("External transfers examined by the reconciliation, by outcome")
        .tag("outcome", outcome.name())
        .register(registry));
    }
    published = Counter.builder(PUBLISHED)
      .description("Outbox notifications confirmed by the broker")
      .register(registry);
    // Micrometer holds `this` with a weak reference: fine while the adapter is a long-lived Spring bean;
    // a local instance with no strong references would see the gauge turn NaN after a garbage collection.
    Gauge.builder(PENDING, this, self -> self.pendingKnown ? self.pending.get() : Double.NaN)
      .description("Rows waiting in the outbox")
      .register(registry);
  }

  private Counter requestCounter(TransferTypeEnum type, TransferStatusEnum status) {
    return requests.computeIfAbsent(type, key -> new ConcurrentHashMap<>())
      .computeIfAbsent(status, key -> Counter.builder(REQUESTS)
        .description("Transfer requests answered to the client, by type and status")
        .tag("type", type.name())
        .tag("status", status.name())
        .register(registry));
  }

  private Counter concludedCounter(TransferTypeEnum type, TransferStatusEnum status) {
    return concluded.computeIfAbsent(type, key -> new ConcurrentHashMap<>())
      .computeIfAbsent(status, key -> Counter.builder(CONCLUDED)
        .description("Transfers that reached COMPLETED or REJECTED, by type and status")
        .tag("type", type.name())
        .tag("status", status.name())
        .register(registry));
  }

  @Override
  public void transferRequested(TransferTypeEnum type, TransferStatusEnum status) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(status, "status");
    requestCounter(type, status).increment();
  }

  @Override
  public void transferConcluded(TransferTypeEnum type, TransferStatusEnum status) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(status, "status");
    concludedCounter(type, status).increment();
  }

  @Override
  public void transferRefunded(RefundCauseEnum cause) {
    Objects.requireNonNull(cause, "cause");
    refunds.get(cause).increment();
  }

  @Override
  public void reconciliationExamined(ReconciliationOutcomeEnum outcome) {
    Objects.requireNonNull(outcome, "outcome");
    reconciliations.get(outcome).increment();
  }

  @Override
  public void notificationPublished() {
    published.increment();
  }

  @Override
  public void outboxPending(long rows) {
    pending.set(rows);
    pendingKnown = true;
  }
}
