package com.bank.transfer.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.support.ProducerListener;

/**
 * Contract section 7: a failed send is logged with everything visible (topic, key, whole payload) except the IBANs,
 * which become the fixed marker "[IBAN]". Applies to the library listener only.
 */
@ExtendWith(OutputCaptureExtension.class)
class IbanRedactingProducerListenerTest {

  private static final String TOPIC = "transfer.notifications";
  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0542811101000000000002";
  private static final String MARKER = "[IBAN]";

  private static final String COMPLETED = "{\"transferId\":\"" + TRANSFER_ID + "\",\"sourceIban\":\"" + SOURCE_IBAN
    + "\",\"destinationIban\":\"" + DEST_IBAN + "\",\"amount\":123.45,\"currency\":\"EUR\","
    + "\"status\":\"COMPLETED\",\"occurredAt\":\"2026-10-13T09:30:00Z\"}";

  private static final String REJECTED = "{\"transferId\":\"" + TRANSFER_ID + "\",\"sourceIban\":\"" + SOURCE_IBAN
    + "\",\"destinationIban\":\"" + DEST_IBAN + "\",\"amount\":20000.00,\"currency\":\"EUR\","
    + "\"status\":\"REJECTED\",\"reasons\":[\"Amount must not exceed 10000 EUR\",\"Source IBAN is locked\"],"
    + "\"occurredAt\":\"2026-10-13T09:31:00Z\"}";

  private final IbanRedactingProducerListener listener = new IbanRedactingProducerListener();

  private static ProducerRecord<String, String> record(String key, String payload) {
    return new ProducerRecord<>(TOPIC, key, payload);
  }

  private static void assertNoIbanAnywhere(CapturedOutput output) {
    assertThat(output.getAll())
      .doesNotContain(SOURCE_IBAN).doesNotContain(DEST_IBAN)
      .doesNotContain("IT60").doesNotContain("0542811101").doesNotContain("000000000001")
      .doesNotContain("000000000002");
  }

  @Test
  void aFailedCompletedNotificationIsLoggedInFullExceptTheIbans(CapturedOutput output) {
    listener.onError(record(TRANSFER_ID, COMPLETED), null, new TimeoutException("neutral-cause"));

    assertThat(output.getAll())
      .contains(TOPIC)
      .contains(TRANSFER_ID)
      .contains("\"amount\":123.45")
      .contains("\"currency\":\"EUR\"")
      .contains("\"status\":\"COMPLETED\"")
      .contains("\"occurredAt\":\"2026-10-13T09:30:00Z\"")
      .contains(MARKER);
    assertNoIbanAnywhere(output);
  }

  @Test
  void bothIbanFieldsOfThePayloadAreReplacedByTheMarker(CapturedOutput output) {
    listener.onError(record(TRANSFER_ID, COMPLETED), null, new TimeoutException("neutral-cause"));

    assertThat(output.getAll())
      .contains("\"sourceIban\":\"" + MARKER + "\"")
      .contains("\"destinationIban\":\"" + MARKER + "\"");
  }

  @Test
  void aFailedRejectedNotificationKeepsStatusAndBothReasonsReadable(CapturedOutput output) {
    listener.onError(record(TRANSFER_ID, REJECTED), null, new TimeoutException("neutral-cause"));

    assertThat(output.getAll())
      .contains(TOPIC)
      .contains(TRANSFER_ID)
      .contains("\"amount\":20000.00")
      .contains("\"currency\":\"EUR\"")
      .contains("\"status\":\"REJECTED\"")
      .contains("Amount must not exceed 10000 EUR")
      .contains("Source IBAN is locked")
      .contains("2026-10-13T09:31:00Z")
      .contains(MARKER);
    assertNoIbanAnywhere(output);
  }

  @Test
  void thePayloadIsNotTruncatedToTheFirstHundredCharacters(CapturedOutput output) {
    assertThat(REJECTED.length()).isGreaterThan(200);

    listener.onError(record(TRANSFER_ID, REJECTED), null, new TimeoutException("neutral-cause"));

    // the end of the payload is far beyond the first 100 characters printed by the library listener
    assertThat(output.getAll()).contains("\"occurredAt\":\"2026-10-13T09:31:00Z\"}");
  }

  @Test
  void anIbanWrittenAnywhereInThePayloadTextIsReplacedNotOnlyTheKnownFields(CapturedOutput output) {
    String payload = "{\"note\":\"paid from " + SOURCE_IBAN + " to " + DEST_IBAN + "\",\"amount\":7.50}";

    listener.onError(record(TRANSFER_ID, payload), null, new TimeoutException("neutral-cause"));

    assertThat(output.getAll()).contains("\"amount\":7.50").contains("paid from " + MARKER + " to " + MARKER);
    assertNoIbanAnywhere(output);
  }

  @Test
  void anIbanUsedAsKeyIsReplacedByTheMarker(CapturedOutput output) {
    listener.onError(record(SOURCE_IBAN, COMPLETED), null, new TimeoutException("neutral-cause"));

    assertNoIbanAnywhere(output);
    assertThat(output.getAll()).contains(MARKER).contains(TOPIC);
  }

  @Test
  void anIbanInTheMessageOfTheExceptionIsReplacedByTheMarker(CapturedOutput output) {
    listener.onError(record(TRANSFER_ID, COMPLETED), null,
      new TimeoutException("send failed for " + SOURCE_IBAN + " and " + DEST_IBAN));

    assertThat(output.getAll()).contains("send failed for " + MARKER + " and " + MARKER);
    assertNoIbanAnywhere(output);
  }

  @Test
  void anExceptionWithoutIbanKeepsItsMessageReadable(CapturedOutput output) {
    listener.onError(record(TRANSFER_ID, COMPLETED), null, new TimeoutException("neutral-cause"));

    assertThat(output.getAll()).contains("neutral-cause");
  }

  @Test
  void aNullKeyDoesNotThrowAndTheRestIsStillLogged(CapturedOutput output) {
    assertThatCode(() -> listener.onError(record(null, COMPLETED), null, new TimeoutException("neutral-cause")))
      .doesNotThrowAnyException();

    assertThat(output.getAll()).contains(TOPIC).contains("\"amount\":123.45");
    assertNoIbanAnywhere(output);
  }

  @Test
  void aNullPayloadDoesNotThrowAndTheRestIsStillLogged(CapturedOutput output) {
    assertThatCode(() -> listener.onError(record(TRANSFER_ID, null), null, new TimeoutException("neutral-cause")))
      .doesNotThrowAnyException();

    assertThat(output.getAll()).contains(TOPIC).contains(TRANSFER_ID);
  }

  @Test
  void aNullKeyAndANullPayloadTogetherDoNotThrow() {
    assertThatCode(() -> listener.onError(record(null, null), null, new TimeoutException("neutral-cause")))
      .doesNotThrowAnyException();
  }

  @Test
  void aNullExceptionMessageDoesNotThrow() {
    assertThatCode(() -> listener.onError(record(TRANSFER_ID, COMPLETED), null, new TimeoutException()))
      .doesNotThrowAnyException();
    assertThatCode(() -> listener.onError(record(TRANSFER_ID, COMPLETED), null, new RuntimeException((String) null)))
      .doesNotThrowAnyException();
  }

  @Test
  void aMissingRecordOrExceptionNeverMakesTheListenerThrow() {
    assertThatCode(() -> listener.onError(null, null, new TimeoutException("neutral-cause")))
      .doesNotThrowAnyException();
    assertThatCode(() -> listener.onError(record(TRANSFER_ID, COMPLETED), null, null))
      .doesNotThrowAnyException();
    assertThatCode(() -> listener.onError(null, null, null)).doesNotThrowAnyException();
  }

  @Test
  void aPayloadThatIsNotJsonIsStillLoggedWithTheIbansRedacted(CapturedOutput output) {
    listener.onError(record(TRANSFER_ID, "plain text " + DEST_IBAN + " end"), null,
      new TimeoutException("neutral-cause"));

    assertThat(output.getAll()).contains("plain text " + MARKER + " end");
    assertNoIbanAnywhere(output);
  }

  // Type robustness: the bootstrap registers this listener as ProducerListener<Object,Object> through a raw cast,
  // so at runtime it can receive keys or values that are not String. The cast below reproduces that situation.
  @SuppressWarnings("unchecked")
  private void sendFailureOf(ProducerRecord<?, ?> record) {
    ProducerListener<Object, Object> raw = (ProducerListener<Object, Object>) (ProducerListener<?, ?>) listener;
    raw.onError((ProducerRecord<Object, Object>) record, null, new TimeoutException("neutral-cause"));
  }

  @Test
  void anIntegerKeyWithAStringPayloadIsStillLoggedAndTheIbansAreRedacted(CapturedOutput output) {
    assertThatCode(() -> sendFailureOf(new ProducerRecord<Object, Object>(TOPIC, 42, COMPLETED)))
      .doesNotThrowAnyException();

    assertThat(output.getAll()).contains(TOPIC).contains("key=42").contains("\"amount\":123.45").contains(MARKER);
    assertNoIbanAnywhere(output);
  }

  @Test
  void aByteArrayPayloadIsLoggedWithItsToStringAndDoesNotThrow(CapturedOutput output) {
    byte[] bytes = COMPLETED.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    assertThatCode(() -> sendFailureOf(new ProducerRecord<Object, Object>(TOPIC, TRANSFER_ID, bytes)))
      .doesNotThrowAnyException();

    assertThat(output.getAll()).contains(TOPIC).contains(TRANSFER_ID).contains("payload=" + bytes);
    assertNoIbanAnywhere(output);
  }

  @Test
  void anIntegerKeyAndAnIntegerPayloadAreBothLogged(CapturedOutput output) {
    assertThatCode(() -> sendFailureOf(new ProducerRecord<Object, Object>(TOPIC, 7, 99)))
      .doesNotThrowAnyException();

    assertThat(output.getAll()).contains(TOPIC).contains("key=7").contains("payload=99");
  }

  @Test
  void anIbanInsideAnObjectPayloadToStringIsReplacedByTheMarker(CapturedOutput output) {
    record Wrapper(String from, String to) { }
    Wrapper wrapper = new Wrapper(SOURCE_IBAN, DEST_IBAN);

    assertThatCode(() -> sendFailureOf(new ProducerRecord<Object, Object>(TOPIC, TRANSFER_ID, wrapper)))
      .doesNotThrowAnyException();

    assertThat(output.getAll()).contains(TOPIC).contains("from=" + MARKER).contains("to=" + MARKER);
    assertNoIbanAnywhere(output);
  }

  @Test
  void anIbanInsideAnObjectKeyToStringIsReplacedByTheMarker(CapturedOutput output) {
    record Wrapper(String iban) { }

    assertThatCode(() -> sendFailureOf(new ProducerRecord<Object, Object>(TOPIC, new Wrapper(SOURCE_IBAN), COMPLETED)))
      .doesNotThrowAnyException();

    assertThat(output.getAll()).contains(TOPIC).contains("iban=" + MARKER);
    assertNoIbanAnywhere(output);
  }

  @Test
  void onSuccessPrintsNothing(CapturedOutput output) {
    listener.onSuccess(record(TRANSFER_ID, COMPLETED), null);

    assertThat(output.getAll()).doesNotContain(TOPIC).doesNotContain(TRANSFER_ID)
      .doesNotContain("\"amount\"").doesNotContain(MARKER);
  }

  @Test
  void onSuccessNeverThrows() {
    assertThatCode(() -> listener.onSuccess(record(null, null), null)).doesNotThrowAnyException();
    assertThatCode(() -> listener.onSuccess(null, null)).doesNotThrowAnyException();
  }
}
