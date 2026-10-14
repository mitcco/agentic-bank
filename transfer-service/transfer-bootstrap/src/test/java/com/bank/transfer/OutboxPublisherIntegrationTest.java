package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.PendingNotificationDto;
import com.bank.transfer.application.dto.PublishFailureStageEnum;
import com.bank.transfer.application.dto.PublishResultDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.exception.NotificationSendException;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.application.port.in.PublishPendingNotificationsInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.NotificationSenderOutPort;
import com.bank.transfer.application.port.out.PendingNotificationOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contract scenarios 13, 14 and 15 on the whole application (dedicated in-memory H2 and Spring context). The
 * publisher is OFF by default, so nobody runs behind our back: the in port is called by hand, which makes every
 * test deterministic. The sender is a controllable fake (OK or FAIL); the real wiring to Kafka is proved by
 * OutboxPublisherKafkaWiringIntegrationTest, which needs its own context.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:outboxpublisher;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
@ExtendWith(OutputCaptureExtension.class)
class OutboxPublisherIntegrationTest {

  private static final String IBAN_PREFIX = "IT60X0542811101000000";
  private static final String AMOUNT = "123.45";
  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  private record Sent(String key, String value) {
  }

  private static final AtomicBoolean SENDER_FAILS = new AtomicBoolean();
  private static final AtomicInteger DELETE_FAILURES_LEFT = new AtomicInteger();
  private static final List<Sent> SENT = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class ControllableCollaboratorsConfig {

    @Bean
    @Primary
    FraudCheckOutPort alwaysAllowedFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) ->
        new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
    }

    @Bean
    @Primary
    NotificationSenderOutPort controllableSender() {
      return (transferId, payload) -> {
        if (SENDER_FAILS.get()) {
          throw new NotificationSendException(transferId);
        }
        SENT.add(new Sent(transferId, payload));
      };
    }

    @Bean
    @Primary
    PendingNotificationOutPort pendingThatCanFailTheDeleteOnce(
      @Qualifier("jpaPendingNotificationOutPortImpl") PendingNotificationOutPort real) {
      return new PendingNotificationOutPort() {
        @Override
        public List<PendingNotificationDto> findPending(int maxRows) {
          return real.findPending(maxRows);
        }

        @Override
        public void delete(long id) {
          if (DELETE_FAILURES_LEFT.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
            throw new IllegalStateException("delete failed");
          }
          real.delete(id);
        }
      };
    }
  }

  @Autowired
  private CreateTransferInPort createTransfer;

  @Autowired
  private PublishPendingNotificationsInPort publish;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private Environment environment;

  private String sourceIban;
  private String destIban;

  @BeforeEach
  void setUp() {
    SENDER_FAILS.set(false);
    DELETE_FAILURES_LEFT.set(0);
    SENT.clear();
    // dedicated database of this class: leftovers of other tests must not be published in this one
    jdbcTemplate.update("DELETE FROM outbox");
    int n = SEQUENCE.incrementAndGet();
    sourceIban = IBAN_PREFIX + "P" + String.format("%05d", n * 2);
    destIban = IBAN_PREFIX + "P" + String.format("%05d", n * 2 + 1);
    accountRepository.save(new Account("acc-p-" + n + "-s", new Iban(sourceIban),
      new Money(new BigDecimal("1000"), "EUR")));
    accountRepository.save(new Account("acc-p-" + n + "-d", new Iban(destIban),
      new Money(BigDecimal.ZERO, "EUR")));
  }

  private TransferResultDto transfer() {
    return createTransfer.executeTransfer(new CreateTransferCommand(sourceIban, destIban, new BigDecimal(AMOUNT), "EUR"));
  }

  private int outboxRowsFor(String transferId) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from outbox where transfer_id = ?",
      Integer.class, transferId);
    return count == null ? 0 : count;
  }

  private String payloadOf(String transferId) {
    return jdbcTemplate.queryForObject("select payload from outbox where transfer_id = ?", String.class, transferId);
  }

  private long rowIdOf(String transferId) {
    Long id = jdbcTemplate.queryForObject("select id from outbox where transfer_id = ?", Long.class, transferId);
    assertThat(id).isNotNull();
    return id;
  }

  private List<Sent> sentFor(String transferId) {
    return SENT.stream().filter(s -> s.key().equals(transferId)).toList();
  }

  private BigDecimal balanceOf(String iban) {
    return accountRepository.findByIban(new Iban(iban)).orElseThrow().money().amount();
  }

  private void assertNoSensitiveDataInTheLog(CapturedOutput output) {
    assertThat(output.getAll()).doesNotContain(sourceIban).doesNotContain(destIban).doesNotContain(AMOUNT);
  }

  @Test
  void thePublisherIsOffInTheseTests() {
    assertThat(environment.getProperty("notification.publisher.enabled")).isEqualTo("false");
  }

  // I1 (scenario 13)
  @Test
  void whenKafkaIsDownTheTransferSucceedsAndTheOutboxRowStays(CapturedOutput output) {
    TransferResultDto result = transfer();
    String transferId = result.transferId();
    SENDER_FAILS.set(true);

    PublishResultDto published = publish.publishPending(100);

    assertThat(result.transferStatus()).isEqualTo("COMPLETED");
    assertThat(balanceOf(sourceIban)).isEqualByComparingTo("876.55");
    assertThat(balanceOf(destIban)).isEqualByComparingTo(AMOUNT);
    assertThat(published.failed()).isTrue();
    assertThat(published.failedTransferId()).isEqualTo(transferId);
    assertThat(published.failureStage()).isEqualTo(PublishFailureStageEnum.SEND);
    assertThat(outboxRowsFor(transferId)).isEqualTo(1);
    assertThat(sentFor(transferId)).isEmpty();
    assertNoSensitiveDataInTheLog(output);
  }

  // I2 (scenario 14)
  @Test
  void whenKafkaIsBackTheRowIsPublishedWithTheTransferIdAsKeyAndThePayloadAsValueAndThenDeleted(
    CapturedOutput output) {
    String transferId = transfer().transferId();
    SENDER_FAILS.set(true);
    publish.publishPending(100);
    String payloadBefore = payloadOf(transferId);
    SENDER_FAILS.set(false);

    PublishResultDto published = publish.publishPending(100);

    assertThat(published.failed()).isFalse();
    List<Sent> sent = sentFor(transferId);
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0).key()).isEqualTo(transferId);
    assertThat(sent.get(0).value()).isEqualTo(payloadBefore);
    assertThat(outboxRowsFor(transferId)).isZero();
    assertNoSensitiveDataInTheLog(output);
  }

  // I3 (scenario 15)
  @Test
  void anErrorAfterTheSendBeforeTheDeleteRepublishesTheSameMessage(CapturedOutput output) {
    String transferId = transfer().transferId();
    String payload = payloadOf(transferId);
    DELETE_FAILURES_LEFT.set(1);

    PublishResultDto first = publish.publishPending(100);

    assertThat(first.failed()).isTrue();
    assertThat(first.failedTransferId()).isEqualTo(transferId);
    assertThat(first.failureStage()).isEqualTo(PublishFailureStageEnum.DELETE);
    assertThat(outboxRowsFor(transferId)).isEqualTo(1);
    assertThat(sentFor(transferId)).hasSize(1);

    PublishResultDto second = publish.publishPending(100);

    assertThat(second.failed()).isFalse();
    assertThat(outboxRowsFor(transferId)).isZero();
    List<Sent> sent = sentFor(transferId);
    assertThat(sent).hasSize(2);
    assertThat(sent.get(1)).isEqualTo(sent.get(0));
    assertThat(sent.get(0).key()).isEqualTo(transferId);
    assertThat(sent.get(0).value()).isEqualTo(payload);
    assertNoSensitiveDataInTheLog(output);
  }

  // I4
  @Test
  void rowsAreSentInIncreasingRowIdOrder() {
    List<String> ids = List.of(transfer().transferId(), transfer().transferId(), transfer().transferId());
    List<String> expectedOrder = ids.stream().sorted(Comparator.comparingLong(this::rowIdOf)).toList();

    PublishResultDto published = publish.publishPending(100);

    assertThat(published.failed()).isFalse();
    List<String> sentOrder = SENT.stream().map(Sent::key).filter(ids::contains).toList();
    assertThat(sentOrder).containsExactlyElementsOf(expectedOrder);
    assertThat(sentOrder).containsExactlyElementsOf(ids);
    ids.forEach(id -> assertThat(outboxRowsFor(id)).isZero());
  }

  // I4 (batch limit: the rest waits for the next round)
  @Test
  void aBatchOfOneLeavesTheOtherRowsForTheNextRound() {
    List<String> ids = List.of(transfer().transferId(), transfer().transferId());

    publish.publishPending(1);

    assertThat(outboxRowsFor(ids.get(0))).isZero();
    assertThat(outboxRowsFor(ids.get(1))).isEqualTo(1);
    assertThat(sentFor(ids.get(1))).isEmpty();

    publish.publishPending(1);

    assertThat(outboxRowsFor(ids.get(1))).isZero();
    assertThat(sentFor(ids.get(1))).hasSize(1);
  }
}
