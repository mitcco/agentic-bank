package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.TransferNotificationDto;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.NotificationOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contract scenario 16: if the notification write fails, the transfer is not saved either (same transaction).
 * The notification port is wrapped: it delegates to the real outbox adapter (so the row IS written) and then fails,
 * which proves that the already inserted row is rolled back too. Dedicated in-memory H2 and Spring context.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:outboxrollback;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class OutboxRollbackIntegrationTest {

  private static final String IBAN_PREFIX = "IT60X0542811101000000";
  private static final String SOURCE_IBAN = IBAN_PREFIX + "R00001";
  private static final String DEST_IBAN = IBAN_PREFIX + "R00002";

  private static final AtomicReference<FraudCheckResultDto> FRAUD_DECISION = new AtomicReference<>();
  private static final List<TransferNotificationDto> RECORDED = new CopyOnWriteArrayList<>();

  @TestConfiguration
  static class FailingNotificationConfig {

    @Bean
    @Primary
    FraudCheckOutPort configurableFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) -> FRAUD_DECISION.get();
    }

    @Bean
    @Primary
    NotificationOutPort failingAfterWritingNotification(
      @Qualifier("jpaOutboxNotificationOutPortImpl") NotificationOutPort real, JdbcTemplate jdbc) {
      return notification -> {
        real.publish(notification);
        // Same DataSource and transaction as the caller: the INSERT must already be visible here, before the rollback.
        Integer rowsInTransaction = jdbc.queryForObject("select count(*) from outbox where transfer_id = ?",
          Integer.class, notification.transferId());
        if (rowsInTransaction == null || rowsInTransaction != 1) {
          throw new AssertionError("expected exactly 1 outbox row inside the transaction before the failure, found "
            + rowsInTransaction);
        }
        RECORDED.add(notification);
        throw new IllegalStateException("outbox write failed");
      };
    }
  }

  @Autowired
  private CreateTransferInPort createTransfer;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void setUp() {
    RECORDED.clear();
    accountRepository.save(new Account("acc-r-1", new Iban(SOURCE_IBAN), new Money(new BigDecimal("1000"), "EUR")));
    accountRepository.save(new Account("acc-r-2", new Iban(DEST_IBAN), new Money(BigDecimal.ZERO, "EUR")));
  }

  private int transferRowsOf(String iban) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from transfer where source_iban = ?",
      Integer.class, iban);
    return count == null ? 0 : count;
  }

  private int outboxRowsFor(String transferId) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from outbox where transfer_id = ?",
      Integer.class, transferId);
    return count == null ? 0 : count;
  }

  private CreateTransferCommand command() {
    return new CreateTransferCommand(SOURCE_IBAN, DEST_IBAN, new BigDecimal("100"), "EUR");
  }

  @Test
  void r1_whenTheNotificationWriteFailsOnACompletedTransferNothingIsSaved() {
    FRAUD_DECISION.set(new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of()));

    assertThatThrownBy(() -> createTransfer.executeTransfer(command()))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("outbox write failed");

    assertThat(RECORDED).hasSize(1);
    String transferId = RECORDED.get(0).transferId();
    assertThat(transferRowsOf(SOURCE_IBAN)).isZero();
    assertThat(outboxRowsFor(transferId)).isZero();
    assertThat(accountRepository.findByIban(new Iban(SOURCE_IBAN)).orElseThrow().money().amount())
      .isEqualByComparingTo("1000");
    assertThat(accountRepository.findByIban(new Iban(DEST_IBAN)).orElseThrow().money().amount())
      .isEqualByComparingTo("0");
  }

  @Test
  void r2_whenTheNotificationWriteFailsOnARejectedTransferNoRejectedTransferIsSaved() {
    FRAUD_DECISION.set(new FraudCheckResultDto(FraudStatusEnum.REJECTED, List.of("Source IBAN is locked")));

    assertThatThrownBy(() -> createTransfer.executeTransfer(command()))
      .isInstanceOf(IllegalStateException.class)
      .hasMessage("outbox write failed");

    assertThat(RECORDED).hasSize(1);
    assertThat(transferRowsOf(SOURCE_IBAN)).isZero();
    assertThat(outboxRowsFor(RECORDED.get(0).transferId())).isZero();
  }
}
