package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.PendingNotificationDto;
import com.bank.transfer.application.dto.PublishFailureStageEnum;
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
 * Contract section 5 and scenarios 13-15 at service level. Hand-written fakes share a single event log, so that the
 * order of the calls between the two ports can be asserted.
 */
class PublishPendingNotificationsServiceImplTest {

  private static final String T1 = "11111111-1111-4111-8111-111111111111";
  private static final String T2 = "22222222-2222-4222-8222-222222222222";
  private static final String T3 = "33333333-3333-4333-8333-333333333333";
  private static final String P1 = "{\"transferId\":\"" + T1 + "\",\"note\":\"first  payload, with spaces\"}";
  private static final String P2 = "{\"transferId\":\"" + T2 + "\",\"note\":\"second\\n\\\"quoted\\\"\"}";
  private static final String P3 = "{\"transferId\":\"" + T3 + "\",\"note\":\"third è\"}";

  private List<String> events;
  private FakePendingPort pending;
  private FakeSenderPort sender;
  private PublishPendingNotificationsServiceImpl service;

  @BeforeEach
  void setUp() {
    events = new ArrayList<>();
    pending = new FakePendingPort(events);
    sender = new FakeSenderPort(events);
    service = new PublishPendingNotificationsServiceImpl(pending, sender);
  }

  private void threeRows() {
    pending.rows.add(new PendingNotificationDto(1L, T1, P1));
    pending.rows.add(new PendingNotificationDto(2L, T2, P2));
    pending.rows.add(new PendingNotificationDto(3L, T3, P3));
  }

  // S1
  @Test
  void everyRowIsSentAndThenDeletedInIdOrder() {
    threeRows();

    PublishResultDto result = service.publishPending(10);

    assertThat(events).containsExactly(
      "findPending(10)",
      "send(" + T1 + ")", "delete(1)",
      "send(" + T2 + ")", "delete(2)",
      "send(" + T3 + ")", "delete(3)");
    assertThat(result.read()).isEqualTo(3);
    assertThat(result.sent()).isEqualTo(3);
    assertThat(result.failed()).isFalse();
    assertThat(result.failedTransferId()).isNull();
    assertThat(result.failureStage()).isNull();
    assertThat(pending.rows).isEmpty();
  }

  // S2
  @Test
  void keyIsTheTransferIdOfTheRowAndValueIsThePayloadUntouched() {
    threeRows();

    service.publishPending(10);

    assertThat(sender.sent).hasSize(3);
    assertThat(sender.sent.get(0).key()).isEqualTo(T1);
    assertThat(sender.sent.get(0).value()).isEqualTo(P1);
    assertThat(sender.sent.get(1).key()).isEqualTo(T2);
    assertThat(sender.sent.get(1).value()).isEqualTo(P2);
    assertThat(sender.sent.get(2).key()).isEqualTo(T3);
    assertThat(sender.sent.get(2).value()).isEqualTo(P3);
  }

  // S3
  @Test
  void noPendingRowsMeansNoSendNoDeleteAndAnEmptyResult() {
    PublishResultDto result = service.publishPending(10);

    assertThat(events).containsExactly("findPending(10)");
    assertThat(result.read()).isZero();
    assertThat(result.sent()).isZero();
    assertThat(result.failed()).isFalse();
  }

  // S4
  @Test
  void theBatchSizeIsPassedToThePendingPort() {
    service.publishPending(5);

    assertThat(pending.requestedMaxRows).containsExactly(5);
  }

  // S4 (boundary)
  @Test
  void aBatchOfOneIsValid() {
    threeRows();

    PublishResultDto result = service.publishPending(1);

    assertThat(pending.requestedMaxRows).containsExactly(1);
    assertThat(result.read()).isEqualTo(1);
    assertThat(result.sent()).isEqualTo(1);
    assertThat(pending.rows).extracting(PendingNotificationDto::id).containsExactly(2L, 3L);
  }

  // S5
  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void aBatchSizeBelowOneIsRejectedWithoutCallingAnyPort(int maxRows) {
    threeRows();

    assertThatThrownBy(() -> service.publishPending(maxRows)).isInstanceOf(IllegalArgumentException.class);

    assertThat(events).isEmpty();
    assertThat(pending.rows).hasSize(3);
  }

  // S6 (scenario 13)
  @Test
  void whenTheFirstSendFailsNothingIsDeletedAndTheOtherRowsAreNotSent() {
    threeRows();
    sender.failWhen = key -> key.equals(T1);

    PublishResultDto result = service.publishPending(10);

    assertThat(events).containsExactly("findPending(10)", "send(" + T1 + ")");
    assertThat(pending.deleted).isEmpty();
    assertThat(pending.rows).hasSize(3);
    assertThat(result.failed()).isTrue();
    assertThat(result.failedTransferId()).isEqualTo(T1);
    assertThat(result.failureStage()).isEqualTo(PublishFailureStageEnum.SEND);
    assertThat(result.sent()).isZero();
    assertThat(result.read()).isEqualTo(3);
  }

  // S7
  @Test
  void whenTheSecondSendFailsTheFirstRowIsDeletedAndTheThirdIsUntouched() {
    threeRows();
    sender.failWhen = key -> key.equals(T2);

    PublishResultDto result = service.publishPending(10);

    assertThat(events).containsExactly(
      "findPending(10)",
      "send(" + T1 + ")", "delete(1)",
      "send(" + T2 + ")");
    assertThat(pending.rows).extracting(PendingNotificationDto::id).containsExactly(2L, 3L);
    assertThat(result.sent()).isEqualTo(1);
    assertThat(result.failed()).isTrue();
    assertThat(result.failedTransferId()).isEqualTo(T2);
    assertThat(result.failureStage()).isEqualTo(PublishFailureStageEnum.SEND);
  }

  // S8 (scenario 14)
  @Test
  void afterABrokerOutageTheNextRunSendsTheRowAndThenDeletesIt() {
    threeRows();
    sender.failWhen = key -> true;
    service.publishPending(10);
    events.clear();
    sender.failWhen = key -> false;

    PublishResultDto result = service.publishPending(10);

    assertThat(events.subList(0, 3)).containsExactly("findPending(10)", "send(" + T1 + ")", "delete(1)");
    assertThat(result.failed()).isFalse();
    assertThat(result.sent()).isEqualTo(3);
    assertThat(pending.rows).isEmpty();
  }

  // S9 (scenario 15)
  @Test
  void whenTheDeleteFailsAfterASuccessfulSendTheRowStaysAndTheRunStops() {
    threeRows();
    pending.failDeleteOf = id -> id == 1L;

    PublishResultDto result = service.publishPending(10);

    assertThat(events).containsExactly("findPending(10)", "send(" + T1 + ")", "delete(1)");
    assertThat(result.failed()).isTrue();
    assertThat(result.failedTransferId()).isEqualTo(T1);
    assertThat(result.failureStage()).isEqualTo(PublishFailureStageEnum.DELETE);
    assertThat(result.sent()).isEqualTo(1);
    assertThat(pending.rows).hasSize(3);
    assertThat(sender.sent).extracting(Sent::key).containsExactly(T1);
  }

  // S10 (scenario 15)
  @Test
  void afterADeleteFailureTheSameMessageIsSentAgainAndThenDeleted() {
    threeRows();
    pending.failDeleteOf = id -> id == 1L;
    service.publishPending(10);
    events.clear();
    pending.failDeleteOf = id -> false;

    service.publishPending(10);

    assertThat(events.subList(0, 3)).containsExactly("findPending(10)", "send(" + T1 + ")", "delete(1)");
    List<Sent> toT1 = sender.sent.stream().filter(s -> s.key().equals(T1)).toList();
    assertThat(toT1).hasSize(2);
    assertThat(toT1.get(1).key()).isEqualTo(toT1.get(0).key());
    assertThat(toT1.get(1).value()).isEqualTo(toT1.get(0).value()).isEqualTo(P1);
    assertThat(pending.rows).isEmpty();
  }

  // S11
  @Test
  void whenReadingFailsTheExceptionPropagatesAndNothingIsSent() {
    pending.failFind = new IllegalStateException("database down");

    assertThatThrownBy(() -> service.publishPending(10))
      .isSameAs(pending.failFind);

    assertThat(sender.sent).isEmpty();
    assertThat(pending.deleted).isEmpty();
  }

  // S12
  @Test
  void anErrorThrownBySendIsNotSwallowed() {
    threeRows();
    AssertionError error = new AssertionError("fatal");
    sender.failWithError = error;

    assertThatThrownBy(() -> service.publishPending(10)).isSameAs(error);

    assertThat(pending.deleted).isEmpty();
  }

  // ------------------------------------------------------------------ fakes

  private record Sent(String key, String value) {
  }

  private static final class FakePendingPort implements PendingNotificationOutPort {

    private final List<String> events;
    final List<PendingNotificationDto> rows = new ArrayList<>();
    final List<Long> deleted = new ArrayList<>();
    final List<Integer> requestedMaxRows = new ArrayList<>();
    Predicate<Long> failDeleteOf = id -> false;
    RuntimeException failFind;

    FakePendingPort(List<String> events) {
      this.events = events;
    }

    @Override
    public List<PendingNotificationDto> findPending(int maxRows) {
      events.add("findPending(" + maxRows + ")");
      requestedMaxRows.add(maxRows);
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
      deleted.add(id);
      rows.removeIf(row -> row.id() == id);
    }
  }

  private static final class FakeSenderPort implements NotificationSenderOutPort {

    private final List<String> events;
    final List<Sent> sent = new ArrayList<>();
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
      sent.add(new Sent(transferId, payload));
    }
  }
}
