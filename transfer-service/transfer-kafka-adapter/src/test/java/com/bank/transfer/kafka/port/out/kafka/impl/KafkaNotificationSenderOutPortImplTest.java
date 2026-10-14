package com.bank.transfer.kafka.port.out.kafka.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bank.transfer.application.exception.NotificationSendException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.ProducerListener;

/**
 * Contract section 5 / scenarios 13-15 at adapter level: the sender returns only after the broker ack and turns every
 * failure into NotificationSendException. A real KafkaTemplate runs on a MockProducer (no broker); the test decides
 * when the ack or the error arrives.
 */
@ExtendWith(OutputCaptureExtension.class)
class KafkaNotificationSenderOutPortImplTest {

  private static final String TOPIC = "t.test";
  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String IBAN = "IT60X0542811101000000000001";
  private static final String PAYLOAD = "{\"transferId\":\"" + TRANSFER_ID + "\",\"sourceIban\":\"" + IBAN
    + "\",\"amount\":100.25,\"currency\":\"EUR\",\"status\":\"COMPLETED\"}";
  /** Text carried by every injected error: a marker, to prove the message of the cause is never logged. */
  private static final String LEAKY_TEXT = "SENSITIVE-CAUSE-MESSAGE";

  /** MockProducer of kafka-clients 4 needs a partitioner; the cluster is empty, so always partition 0. */
  static final class FirstPartitionPartitioner implements Partitioner {
    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes,
      Cluster cluster) {
      return 0;
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(Map<String, ?> configs) {
    }
  }

  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    executor = Executors.newCachedThreadPool();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  private static MockProducer<String, String> mockProducer(boolean autoComplete) {
    return new MockProducer<>(autoComplete, new FirstPartitionPartitioner(), new StringSerializer(), new StringSerializer());
  }

  /** The producer is shared and must survive KafkaTemplate closing it after each send (like Boot's factory does). */
  @SuppressWarnings("unchecked")
  private static KafkaTemplate<String, String> template(MockProducer<String, String> mock) {
    Producer<String, String> nonClosing = (Producer<String, String>) Proxy.newProxyInstance(
      KafkaNotificationSenderOutPortImplTest.class.getClassLoader(), new Class<?>[] {Producer.class},
      (proxy, method, args) -> {
        if (method.getName().equals("close")) {
          return null;
        }
        try {
          return method.invoke(mock, args);
        } catch (InvocationTargetException e) {
          throw e.getCause();
        }
      });
    KafkaTemplate<String, String> template = new KafkaTemplate<>(() -> nonClosing);
    // the library listener would log key and payload head: this test checks only the logs of the sender itself
    template.setProducerListener(new ProducerListener<>() {
    });
    return template;
  }

  private static KafkaNotificationSenderOutPortImpl sender(KafkaOperations<String, String> kafka, Duration timeout) {
    return new KafkaNotificationSenderOutPortImpl(kafka, TOPIC, timeout);
  }

  private static void assertNothingSensitiveInTheLog(CapturedOutput output) {
    assertThat(output.getAll()).contains(TRANSFER_ID).doesNotContain(PAYLOAD).doesNotContain(IBAN)
      .doesNotContain("100.25").doesNotContain(LEAKY_TEXT);
  }

  // K1
  @Test
  void sendsOneRecordToTheTopicWithTheTransferIdAsKeyAndThePayloadUntouched() {
    MockProducer<String, String> mock = mockProducer(true);

    sender(template(mock), Duration.ofSeconds(5)).send(TRANSFER_ID, PAYLOAD);

    assertThat(mock.history()).hasSize(1);
    ProducerRecord<String, String> record = mock.history().get(0);
    assertThat(record.topic()).isEqualTo(TOPIC);
    assertThat(record.key()).isEqualTo(TRANSFER_ID);
    assertThat(record.value()).isEqualTo(PAYLOAD);
  }

  // K2
  @Test
  void sendDoesNotReturnBeforeTheBrokerAckAndReturnsNormallyAfterIt() throws Exception {
    MockProducer<String, String> mock = mockProducer(false);
    KafkaNotificationSenderOutPortImpl port = sender(template(mock), Duration.ofSeconds(10));

    Future<?> call = executor.submit(() -> port.send(TRANSFER_ID, PAYLOAD));
    await().atMost(5, TimeUnit.SECONDS).until(() -> mock.history().size() == 1);
    Thread.sleep(200);
    assertThat(call.isDone()).as("no ack yet, send must still be waiting").isFalse();

    assertThat(mock.completeNext()).isTrue();

    call.get(5, TimeUnit.SECONDS);
    assertThat(call.isDone()).isTrue();
  }

  // K3
  @Test
  void aBrokerErrorAfterTheSendBecomesANotificationSendExceptionWithTheTransferId(CapturedOutput output)
    throws Exception {
    MockProducer<String, String> mock = mockProducer(false);
    KafkaNotificationSenderOutPortImpl port = sender(template(mock), Duration.ofSeconds(10));

    Future<?> call = executor.submit(() -> port.send(TRANSFER_ID, PAYLOAD));
    await().atMost(5, TimeUnit.SECONDS).until(() -> mock.history().size() == 1);
    mock.errorNext(new TimeoutException(LEAKY_TEXT));

    assertThatThrownBy(() -> call.get(5, TimeUnit.SECONDS))
      .hasCauseInstanceOf(NotificationSendException.class)
      .cause()
      .satisfies(e -> assertThat(String.valueOf(e.getMessage())).contains(TRANSFER_ID)
        .doesNotContain(IBAN).doesNotContain("100.25").doesNotContain(LEAKY_TEXT));
    assertNothingSensitiveInTheLog(output);
  }

  // K4
  @Test
  void withoutAnAckWithinTheSendTimeoutItFailsQuickly(CapturedOutput output) {
    MockProducer<String, String> mock = mockProducer(false);
    KafkaNotificationSenderOutPortImpl port = sender(template(mock), Duration.ofMillis(100));

    long start = System.nanoTime();
    assertThatThrownBy(() -> port.send(TRANSFER_ID, PAYLOAD))
      .isInstanceOf(NotificationSendException.class)
      .hasMessageContaining(TRANSFER_ID);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
    assertNothingSensitiveInTheLog(output);
  }

  // K5
  @Test
  @SuppressWarnings("unchecked")
  void aSynchronousKafkaExceptionBecomesANotificationSendException(CapturedOutput output) {
    // every method of the operations throws, whatever the overload of send the adapter picks
    KafkaOperations<String, String> failing = Mockito.mock(KafkaOperations.class, invocation -> {
      throw new KafkaException(LEAKY_TEXT);
    });

    assertThatThrownBy(() -> sender(failing, Duration.ofSeconds(1)).send(TRANSFER_ID, PAYLOAD))
      .isInstanceOf(NotificationSendException.class)
      .hasMessageContaining(TRANSFER_ID)
      .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(IBAN).doesNotContain(LEAKY_TEXT));
    assertNothingSensitiveInTheLog(output);
  }

  // K6
  @Test
  void anInterruptionWhileWaitingFailsTheSendAndKeepsTheInterruptFlag() throws Exception {
    MockProducer<String, String> mock = mockProducer(false);
    KafkaNotificationSenderOutPortImpl port = sender(template(mock), Duration.ofSeconds(30));
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean flagAfter = new AtomicBoolean();
    Thread caller = new Thread(() -> {
      try {
        port.send(TRANSFER_ID, PAYLOAD);
      } catch (Throwable t) {
        thrown.set(t);
        flagAfter.set(Thread.currentThread().isInterrupted());
      }
    });
    caller.start();
    await().atMost(5, TimeUnit.SECONDS).until(() -> mock.history().size() == 1);

    caller.interrupt();
    caller.join(5_000);

    assertThat(caller.isAlive()).isFalse();
    assertThat(thrown.get()).isInstanceOf(NotificationSendException.class);
    assertThat(flagAfter.get()).as("interrupt flag restored").isTrue();
  }

  // K8
  @ParameterizedTest
  @NullAndEmptySource
  void aMissingTopicIsRejectedByTheConstructor(String topic) {
    KafkaTemplate<String, String> kafka = template(mockProducer(true));

    assertThatThrownBy(() -> new KafkaNotificationSenderOutPortImpl(kafka, topic, Duration.ofSeconds(1)))
      .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
  }

  // K8
  @ParameterizedTest
  @ValueSource(longs = {0, -1})
  void aZeroOrNegativeSendTimeoutIsRejectedByTheConstructor(long millis) {
    KafkaTemplate<String, String> kafka = template(mockProducer(true));

    assertThatThrownBy(() -> new KafkaNotificationSenderOutPortImpl(kafka, TOPIC, Duration.ofMillis(millis)))
      .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
  }

  // K8
  @Test
  void aMissingSendTimeoutIsRejectedByTheConstructor() {
    KafkaTemplate<String, String> kafka = template(mockProducer(true));

    assertThatThrownBy(() -> new KafkaNotificationSenderOutPortImpl(kafka, TOPIC, null))
      .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
  }
}
