package com.bank.transfer.application.service;

import static com.bank.transfer.application.service.RecordingMetricsOutPort.published;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.PendingNotificationDto;
import com.bank.transfer.application.dto.PublishResultDto;
import com.bank.transfer.application.exception.NotificationSendException;
import com.bank.transfer.application.port.out.NotificationSenderOutPort;
import com.bank.transfer.application.port.out.PendingNotificationOutPort;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Metrica delle notifiche pubblicate (piano transfer-14, D6): una chiamata per ogni send confermato dal broker,
 * subito dopo il send e prima del delete, anche se il delete poi fallisce (at-least-once).
 * Fake scritti a mano che condividono un solo registro di eventi, per verificare anche l'ordine.
 */
class PublishPendingNotificationsMetricsTest {

  private static final String T1 = "11111111-1111-4111-8111-111111111111";
  private static final String T2 = "22222222-2222-4222-8222-222222222222";
  private static final String T3 = "33333333-3333-4333-8333-333333333333";

  private List<String> events;
  private FakePendingPort pending;
  private FakeSenderPort sender;
  private RecordingMetricsOutPort metrics;
  private PublishPendingNotificationsServiceImpl service;

  @BeforeEach
  void setUp() {
    events = new ArrayList<>();
    pending = new FakePendingPort(events);
    sender = new FakeSenderPort(events);
    metrics = new RecordingMetricsOutPort() {
      @Override
      public void notificationPublished() {
        events.add("metric");
        super.notificationPublished();
      }
    };
    service = new PublishPendingNotificationsServiceImpl(pending, sender, metrics);
  }

  private void threeRows() {
    pending.rows.add(new PendingNotificationDto(1L, T1, "{\"transferId\":\"" + T1 + "\"}"));
    pending.rows.add(new PendingNotificationDto(2L, T2, "{\"transferId\":\"" + T2 + "\"}"));
    pending.rows.add(new PendingNotificationDto(3L, T3, "{\"transferId\":\"" + T3 + "\"}"));
  }

  @Test
  void threeRowsAllOkCountThreePublishedNotifications() {
    threeRows();

    PublishResultDto result = service.publishPending(10);

    assertThat(result.sent()).isEqualTo(3);
    assertThat(metrics.calls()).containsExactly(published(), published(), published());
  }

  @Test
  void eachNotificationIsCountedRightAfterItsSendAndBeforeItsDelete() {
    threeRows();

    service.publishPending(10);

    assertThat(events).containsExactly(
      "findPending(10)",
      "send(" + T1 + ")", "metric", "delete(1)",
      "send(" + T2 + ")", "metric", "delete(2)",
      "send(" + T3 + ")", "metric", "delete(3)");
  }

  @Test
  void aFailingSendOnTheSecondRowCountsOnlyTheFirst() {
    threeRows();
    sender.failWhen = key -> key.equals(T2);

    PublishResultDto result = service.publishPending(10);

    assertThat(result.failed()).isTrue();
    assertThat(metrics.calls()).containsExactly(published());
  }

  @Test
  void aFailingSendOnTheFirstRowCountsNothing() {
    threeRows();
    sender.failWhen = key -> key.equals(T1);

    service.publishPending(10);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void aFailingDeleteOnTheSecondRowStillCountsTheTwoConfirmedSends() {
    threeRows();
    pending.failDeleteOf = id -> id == 2L;

    PublishResultDto result = service.publishPending(10);

    assertThat(result.failed()).isTrue();
    assertThat(metrics.calls()).containsExactly(published(), published());
  }

  @Test
  void aFailingDeleteOnTheFirstRowStillCountsTheConfirmedSend() {
    threeRows();
    pending.failDeleteOf = id -> id == 1L;

    service.publishPending(10);

    assertThat(metrics.calls()).containsExactly(published());
  }

  @Test
  void theSameRowResentAfterAFailedDeleteIsCountedAgain() {
    threeRows();
    pending.failDeleteOf = id -> id == 1L;
    service.publishPending(10);
    pending.failDeleteOf = id -> false;

    service.publishPending(10);

    // 1 (first run, delete failed) + 3 (second run: the first row again, then the other two)
    assertThat(metrics.calls()).hasSize(4);
  }

  @Test
  void noPendingRowsCountNothing() {
    PublishResultDto result = service.publishPending(10);

    assertThat(result.read()).isZero();
    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void aFailingReadCountsNothing() {
    pending.failFind = new IllegalStateException("database down");

    assertThatThrownBy(() -> service.publishPending(10)).isSameAs(pending.failFind);

    assertThat(metrics.calls()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void aBatchSizeBelowOneIsRefusedAndCountsNothing(int maxRows) {
    threeRows();

    assertThatThrownBy(() -> service.publishPending(maxRows)).isInstanceOf(IllegalArgumentException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void aBatchOfOneCountsOnlyOneNotification() {
    threeRows();

    service.publishPending(1);

    assertThat(metrics.calls()).containsExactly(published());
  }

  @Test
  void anErrorThrownBySendIsNotSwallowedAndCountsNothing() {
    threeRows();
    AssertionError error = new AssertionError("fatal");
    sender.failWithError = error;

    assertThatThrownBy(() -> service.publishPending(10)).isSameAs(error);

    assertThat(metrics.calls()).isEmpty();
  }

  // ------------------------------------------------------------------ fakes

  private static final class FakePendingPort implements PendingNotificationOutPort {

    private final List<String> events;
    final List<PendingNotificationDto> rows = new ArrayList<>();
    Predicate<Long> failDeleteOf = id -> false;
    RuntimeException failFind;

    FakePendingPort(List<String> events) {
      this.events = events;
    }

    @Override
    public List<PendingNotificationDto> findPending(int maxRows) {
      events.add("findPending(" + maxRows + ")");
      if (failFind != null) {
        throw failFind;
      }
      return new ArrayList<>(rows.subList(0, Math.min(maxRows, rows.size())));
    }

    @Override
    public void delete(long id) {
      events.add("delete(" + id + ")");
      if (failDeleteOf.test(id)) {
        throw new IllegalStateException("delete failed");
      }
      rows.removeIf(row -> row.id() == id);
    }
  }

  private static final class FakeSenderPort implements NotificationSenderOutPort {

    private final List<String> events;
    Predicate<String> failWhen = key -> false;
    Error failWithError;

    FakeSenderPort(List<String> events) {
      this.events = events;
    }

    @Override
    public void send(String transferId, String payload) {
      events.add("send(" + transferId + ")");
      if (failWithError != null) {
        throw failWithError;
      }
      if (failWhen.test(transferId)) {
        throw new NotificationSendException(transferId);
      }
    }
  }
}
