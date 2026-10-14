package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.PublishResultDto;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.application.port.in.PublishPendingNotificationsInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * I5: the REAL wiring of the bootstrap (NotificationSenderOutPort on the KafkaTemplate, topic taken from
 * notification.topic) with a MockProducer instead of the broker. It needs a context of its own, because
 * OutboxPublisherIntegrationTest replaces the sender with a fake. The publisher stays OFF, the in port is called by
 * hand. Dedicated in-memory H2.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:outboxpublisherkafka;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class OutboxPublisherKafkaWiringIntegrationTest {

  private static final String IBAN_PREFIX = "IT60X0542811101000000";
  private static final String SOURCE_IBAN = IBAN_PREFIX + "K00001";
  private static final String DEST_IBAN = IBAN_PREFIX + "K00002";

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

  private static final MockProducer<String, String> PRODUCER =
    new MockProducer<>(true, new FirstPartitionPartitioner(), new StringSerializer(), new StringSerializer());

  @TestConfiguration
  static class MockBrokerConfig {

    @Bean
    @Primary
    FraudCheckOutPort alwaysAllowedFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) ->
        new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
    }

    /** Boot's own KafkaTemplate backs off (it is conditional on a missing bean). */
    @Bean
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafkaTemplate() {
      // the producer is shared: KafkaTemplate must not close it after each send
      Producer<String, String> nonClosing = (Producer<String, String>) Proxy.newProxyInstance(
        OutboxPublisherKafkaWiringIntegrationTest.class.getClassLoader(), new Class<?>[] {Producer.class},
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
      return new KafkaTemplate<>(() -> nonClosing);
    }
  }

  @Value("${notification.topic}")
  private String topic;

  @Autowired
  private CreateTransferInPort createTransfer;

  @Autowired
  private PublishPendingNotificationsInPort publish;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    PRODUCER.clear();
    jdbcTemplate.update("DELETE FROM outbox");
    accountRepository.save(new Account("acc-k-1", new Iban(SOURCE_IBAN), new Money(new BigDecimal("1000"), "EUR")));
    accountRepository.save(new Account("acc-k-2", new Iban(DEST_IBAN), new Money(BigDecimal.ZERO, "EUR")));
  }

  // I5
  @Test
  void theRealSenderPublishesOnTheConfiguredTopicWithTheRightKeyAndValueAndTheRowIsDeleted() {
    assertThat(topic).isEqualTo("transfer.notifications");
    String transferId = createTransfer
      .executeTransfer(new CreateTransferCommand(SOURCE_IBAN, DEST_IBAN, new BigDecimal("100"), "EUR"))
      .transferId();
    String payload = jdbcTemplate.queryForObject("select payload from outbox where transfer_id = ?", String.class,
      transferId);

    PublishResultDto result = publish.publishPending(100);

    assertThat(result.failed()).isFalse();
    List<ProducerRecord<String, String>> records = PRODUCER.history().stream()
      .filter(r -> transferId.equals(r.key())).toList();
    assertThat(records).hasSize(1);
    assertThat(records.get(0).topic()).isEqualTo(topic);
    assertThat(records.get(0).value()).isEqualTo(payload);
    Integer rows = jdbcTemplate.queryForObject("select count(*) from outbox where transfer_id = ?", Integer.class,
      transferId);
    assertThat(rows).isZero();
  }
}
