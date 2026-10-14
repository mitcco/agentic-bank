package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.kafka.config.IbanRedactingProducerListener;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Contract section 7 on the real wiring: the only ProducerListener of the context is the IBAN redacting one, Boot's
 * own KafkaTemplate uses it, and a failed send through that template logs the amount but not the IBANs. Only the
 * ProducerFactory is replaced (MockProducer instead of the broker): Boot's KafkaTemplate is kept. Dedicated H2.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:kafkalistener;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class KafkaProducerListenerWiringIntegrationTest {

  private static final String TOPIC = "transfer.notifications";
  private static final String TRANSFER_ID = "3f2b8c1e-9a47-4d6b-b5e2-1c8a7d0f4e93";
  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0542811101000000000002";
  private static final String PAYLOAD = "{\"transferId\":\"" + TRANSFER_ID + "\",\"sourceIban\":\"" + SOURCE_IBAN
    + "\",\"destinationIban\":\"" + DEST_IBAN + "\",\"amount\":123.45,\"currency\":\"EUR\","
    + "\"status\":\"COMPLETED\",\"occurredAt\":\"2026-10-13T09:30:00Z\"}";

  /** Boot's KafkaTemplate needs a ProducerFactory<Object, Object>: the payloads are Strings, serialized as UTF-8. */
  private static final Serializer<Object> OBJECT_SERIALIZER =
    (topic, data) -> data == null ? null : data.toString().getBytes(StandardCharsets.UTF_8);

  private static final MockProducer<Object, Object> PRODUCER = new MockProducer<>(false,
    new OutboxPublisherKafkaWiringIntegrationTest.FirstPartitionPartitioner(), OBJECT_SERIALIZER,
    OBJECT_SERIALIZER);

  @TestConfiguration
  static class MockBrokerConfig {

    @Bean
    @Primary
    FraudCheckOutPort alwaysAllowedFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) ->
        new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
    }

    /** Only the factory is replaced: Boot's KafkaTemplate (and its listener wiring) stays in place. */
    @Bean
    @SuppressWarnings("unchecked")
    ProducerFactory<Object, Object> mockProducerFactory() {
      // the producer is shared: KafkaTemplate must not close it after each send
      Producer<Object, Object> nonClosing = (Producer<Object, Object>) Proxy.newProxyInstance(
        KafkaProducerListenerWiringIntegrationTest.class.getClassLoader(), new Class<?>[] {Producer.class},
        (proxy, method, args) -> {
          if (method.getName().equals("close")) {
            return null;
          }
          try {
            return method.invoke(PRODUCER, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        });
      return () -> nonClosing;
    }
  }

  @Autowired
  private ApplicationContext context;

  @Autowired
  private KafkaTemplate<?, ?> kafkaTemplate;

  @BeforeEach
  void setUp() {
    PRODUCER.clear();
  }

  @Test
  void theContextHasExactlyOneProducerListenerAndItIsTheIbanRedactingOne() {
    assertThat(context.getBeansOfType(ProducerListener.class)).hasSize(1);
    assertThat(context.getBeansOfType(ProducerListener.class).values().iterator().next())
      .isInstanceOf(IbanRedactingProducerListener.class);
  }

  @Test
  void theKafkaTemplateOfBootUsesTheIbanRedactingListener() {
    Object used = ReflectionTestUtils.getField(kafkaTemplate, "producerListener");

    assertThat(used).isInstanceOf(IbanRedactingProducerListener.class);
    assertThat(used).isSameAs(context.getBean(ProducerListener.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aFailedSendThroughTheRealTemplateLogsTheAmountButNeitherIban(CapturedOutput output) {
    KafkaTemplate<String, String> template = (KafkaTemplate<String, String>) kafkaTemplate;

    template.send(TOPIC, TRANSFER_ID, PAYLOAD);
    assertThat(PRODUCER.history()).hasSize(1);
    PRODUCER.errorNext(new TimeoutException("neutral-cause"));

    assertThat(output.getAll())
      .contains("\"amount\":123.45")
      .contains("\"status\":\"COMPLETED\"")
      .contains(TRANSFER_ID)
      .contains("[IBAN]")
      .doesNotContain(SOURCE_IBAN).doesNotContain(DEST_IBAN)
      .doesNotContain("0542811101").doesNotContain("000000000001").doesNotContain("000000000002");
  }
}
