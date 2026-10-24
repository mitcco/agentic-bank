package com.bank.transfer.application.service;

import static com.bank.transfer.application.service.RecordingMetricsOutPort.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.port.out.PendingNotificationCountOutPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Aggiornamento del gauge delle righe dell'outbox in attesa (piano transfer-14, D4): il service legge il conteggio
 * e lo passa alla porta delle metriche; le eccezioni le lascia allo scheduler.
 */
class RefreshOutboxMetricsServiceImplTest {

  private FakeCountPort count;
  private RecordingMetricsOutPort metrics;
  private RefreshOutboxMetricsServiceImpl service;

  @BeforeEach
  void setUp() {
    count = new FakeCountPort();
    metrics = new RecordingMetricsOutPort();
    service = new RefreshOutboxMetricsServiceImpl(count, metrics);
  }

  @Test
  void sevenPendingRowsAreReportedAsSeven() {
    count.rows = 7;

    service.refreshPendingNotifications();

    assertThat(metrics.calls()).containsExactly(pending(7));
  }

  @Test
  void zeroPendingRowsAreReportedAsZero() {
    count.rows = 0;

    service.refreshPendingNotifications();

    assertThat(metrics.calls()).containsExactly(pending(0));
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 99, 100, 10_000_000_000L, Long.MAX_VALUE})
  void theCountIsPassedUnchanged(long rows) {
    count.rows = rows;

    service.refreshPendingNotifications();

    assertThat(metrics.calls()).containsExactly(pending(rows));
  }

  @Test
  void eachRefreshReadsTheCountAgainAndReportsTheNewValue() {
    count.rows = 5;
    service.refreshPendingNotifications();
    count.rows = 2;
    service.refreshPendingNotifications();

    assertThat(count.calls).isEqualTo(2);
    assertThat(metrics.calls()).containsExactly(pending(5), pending(2));
  }

  @Test
  void aFailingCountPropagatesTheExceptionAndReportsNothing() {
    count.failure = new IllegalStateException("database down");

    assertThatThrownBy(() -> service.refreshPendingNotifications()).isSameAs(count.failure);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void afterAFailureTheNextRefreshWorks() {
    count.failure = new IllegalStateException("database down");
    assertThatThrownBy(() -> service.refreshPendingNotifications()).isInstanceOf(IllegalStateException.class);
    count.failure = null;
    count.rows = 3;

    service.refreshPendingNotifications();

    assertThat(metrics.calls()).containsExactly(pending(3));
  }

  @Test
  void constructorRefusesNullArguments() {
    assertThatThrownBy(() -> new RefreshOutboxMetricsServiceImpl(null, metrics))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RefreshOutboxMetricsServiceImpl(count, null))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RefreshOutboxMetricsServiceImpl(null, null))
      .isInstanceOf(NullPointerException.class);
  }

  private static final class FakeCountPort implements PendingNotificationCountOutPort {

    long rows;
    int calls;
    RuntimeException failure;

    @Override
    public long countPending() {
      calls++;
      if (failure != null) {
        throw failure;
      }
      return rows;
    }
  }
}
