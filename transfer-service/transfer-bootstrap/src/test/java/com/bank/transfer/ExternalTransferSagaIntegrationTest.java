package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.port.in.CreateExternalTransferInPort;
import com.bank.transfer.application.port.in.CreateInternalTransferInPort;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.application.port.in.ProcessClearingOutcomesInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.ClearingHouseOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.service.CreateTransferServiceImpl;
import com.bank.transfer.application.service.ExternalTransferSagaServiceImpl;
import com.bank.transfer.application.service.TransferRoutingServiceImpl;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingHouseOutPortImpl;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingModeEnum;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.BankCode;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Saga of the external transfers with the real service, the real JPA adapters, the simulated clearing house
 * and a dedicated in-memory H2 database. The scheduler is off (the default): the test calls processOutcomes()
 * by hand. Every test has its own source account, so the tests do not depend on each other.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:sagaintegration;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class ExternalTransferSagaIntegrationTest {

  /** The fraud check is not what is tested here: it always allows, without any network. */
  @TestConfiguration
  static class AlwaysAllowedFraudCheck {

    @Bean
    @Primary
    FraudCheckOutPort alwaysAllowedFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) ->
        new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
    }
  }

  /** ABI 05428: an account of this bank. */
  private static final String IBAN_PREFIX = "IT60X0542811101000000";
  /** ABI 03069: another bank, not an account of ours. */
  private static final String EXTERNAL_IBAN = "IT60X0306909606100000012345";
  private static final String UNAVAILABLE_REASON = "Clearing house unavailable";
  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  @Autowired
  private ApplicationContext context;

  @Autowired
  private CreateExternalTransferInPort createExternalTransfer;

  @Autowired
  private ProcessClearingOutcomesInPort processClearingOutcomes;

  @Autowired
  private SimulatedClearingHouseOutPortImpl simulator;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private String sourceIban;

  @BeforeEach
  void setUp() {
    // the simulator is a singleton shared by the tests: back to ACCEPT and with nothing left in its queue
    simulator.setMode(SimulatedClearingModeEnum.ACCEPT);
    simulator.pollOutcomes();

    int number = SEQUENCE.incrementAndGet();
    sourceIban = IBAN_PREFIX + String.format("G%05d", number);
    accountRepository.save(
      new Account("acc-saga-" + number, new Iban(sourceIban), new Money(new BigDecimal("1000"), "EUR")));
  }

  private TransferResultDto externalTransfer(String amount) {
    return createExternalTransfer.executeExternalTransfer(
      new CreateTransferCommand(sourceIban, EXTERNAL_IBAN, new BigDecimal(amount), "EUR"));
  }

  private BigDecimal balance() {
    return accountRepository.findByIban(new Iban(sourceIban)).orElseThrow().money().amount();
  }

  private String externalStatus(String transferId) {
    return jdbcTemplate.queryForObject("select status from external_transfer where id = ?", String.class,
      transferId);
  }

  private String externalReason(String transferId) {
    return jdbcTemplate.queryForObject("select reason from external_transfer where id = ?", String.class,
      transferId);
  }

  private int count(String sql, Object... args) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
    assertThat(count).isNotNull();
    return count;
  }

  private int externalRows(String transferId) {
    return count("select count(*) from external_transfer where id = ?", transferId);
  }

  private int outboxRows(String transferId) {
    return count("select count(*) from outbox where transfer_id = ?", transferId);
  }

  private String outboxPayload(String transferId) {
    return jdbcTemplate.queryForObject("select payload from outbox where transfer_id = ?", String.class,
      transferId);
  }

  @Test
  void wiringHasOneBeanPerPortAndNoScheduler() throws Exception {
    assertThat(context.getBeansOfType(CreateTransferInPort.class)).hasSize(1);
    assertThat(context.getBean(CreateTransferInPort.class)).isInstanceOf(TransferRoutingServiceImpl.class);
    assertThat(context.getBeansOfType(CreateInternalTransferInPort.class)).hasSize(1);
    assertThat(context.getBean(CreateInternalTransferInPort.class)).isInstanceOf(CreateTransferServiceImpl.class);
    assertThat(context.getBean(BankCode.class)).isEqualTo(new BankCode("05428"));

    assertThat(context.getBeansOfType(CreateExternalTransferInPort.class)).hasSize(1);
    assertThat(context.getBeansOfType(ProcessClearingOutcomesInPort.class)).hasSize(1);
    assertThat(createExternalTransfer).isInstanceOf(ExternalTransferSagaServiceImpl.class);
    assertThat(processClearingOutcomes).isSameAs(createExternalTransfer);

    assertThat(context.getBeansOfType(ClearingHouseOutPort.class)).hasSize(1);
    assertThat(context.getBean(ClearingHouseOutPort.class)).isSameAs(simulator);

    Class<?> scheduler = Class.forName("com.bank.transfer.config.ClearingOutcomesScheduler");
    assertThat(context.getBeansOfType(scheduler)).isEmpty();
  }

  @Test
  void acceptedExternalTransferIsDebitedThenCompleted() {
    TransferResultDto sent = externalTransfer("123.45");

    assertThat(sent.transferStatus()).isEqualTo("SENT");
    assertThat(sent.reasons()).isEmpty();
    assertThat(balance()).isEqualByComparingTo("876.55");
    assertThat(externalStatus(sent.transferId())).isEqualTo("SENT");
    assertThat(outboxRows(sent.transferId())).isZero();

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(externalStatus(sent.transferId())).isEqualTo("COMPLETED");
    assertThat(outboxRows(sent.transferId())).isEqualTo(1);
    assertThat(outboxPayload(sent.transferId())).contains("\"status\":\"COMPLETED\"");
    assertThat(balance()).isEqualByComparingTo("876.55");
  }

  @Test
  void rejectedExternalTransferIsRefunded() {
    simulator.setMode(SimulatedClearingModeEnum.REJECT);

    TransferResultDto sent = externalTransfer("123.45");

    assertThat(sent.transferStatus()).isEqualTo("SENT");
    assertThat(balance()).isEqualByComparingTo("876.55");

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(externalStatus(sent.transferId())).isEqualTo("REJECTED");
    assertThat(externalReason(sent.transferId())).isEqualTo(SimulatedClearingHouseOutPortImpl.REJECT_REASON);
    assertThat(balance()).isEqualByComparingTo("1000");
    assertThat(outboxRows(sent.transferId())).isEqualTo(1);
    assertThat(outboxPayload(sent.transferId())).contains("\"status\":\"REJECTED\"");
  }

  @Test
  void secondRoundDoesNotRefundTwice() {
    simulator.setMode(SimulatedClearingModeEnum.REJECT);
    TransferResultDto sent = externalTransfer("123.45");

    int firstRound = processClearingOutcomes.processOutcomes();
    int secondRound = processClearingOutcomes.processOutcomes();

    assertThat(firstRound).isEqualTo(1);
    assertThat(secondRound).isZero();
    assertThat(balance()).isEqualByComparingTo("1000");
    assertThat(outboxRows(sent.transferId())).isEqualTo(1);
  }

  @Test
  void unavailableClearingIsCompensatedImmediately() {
    simulator.setMode(SimulatedClearingModeEnum.UNAVAILABLE);

    TransferResultDto result = externalTransfer("123.45");

    assertThat(result.transferStatus()).isEqualTo("REJECTED");
    assertThat(result.reasons()).containsExactly(UNAVAILABLE_REASON);
    assertThat(balance()).isEqualByComparingTo("1000");
    assertThat(externalStatus(result.transferId())).isEqualTo("REJECTED");
    assertThat(externalReason(result.transferId())).isEqualTo(UNAVAILABLE_REASON);
    assertThat(outboxRows(result.transferId())).isEqualTo(1);
    assertThat(outboxPayload(result.transferId())).contains("\"status\":\"REJECTED\"");
    // nothing was queued by the clearing house: there is nothing to apply
    assertThat(processClearingOutcomes.processOutcomes()).isZero();
    assertThat(balance()).isEqualByComparingTo("1000");
  }

  @Test
  void noTransferRowIsWrittenForExternalTransfers() {
    TransferResultDto accepted = externalTransfer("10");
    simulator.setMode(SimulatedClearingModeEnum.REJECT);
    TransferResultDto rejected = externalTransfer("20");
    simulator.setMode(SimulatedClearingModeEnum.UNAVAILABLE);
    TransferResultDto unavailable = externalTransfer("30");
    processClearingOutcomes.processOutcomes();

    assertThat(count("select count(*) from transfer where source_iban = ?", sourceIban)).isZero();
    assertThat(externalRows(accepted.transferId())).isEqualTo(1);
    assertThat(externalRows(rejected.transferId())).isEqualTo(1);
    assertThat(externalRows(unavailable.transferId())).isEqualTo(1);
  }

  @Test
  void timeoutThenAcceptGoesUnknownAndIsCompletedByTheLateOutcome() {
    simulator.setMode(SimulatedClearingModeEnum.TIMEOUT_THEN_ACCEPT);

    TransferResultDto result = externalTransfer("123.45");

    assertThat(result.transferStatus()).isEqualTo("UNKNOWN");
    assertThat(result.reasons()).isEmpty();
    assertThat(balance()).isEqualByComparingTo("876.55");
    assertThat(externalStatus(result.transferId())).isEqualTo("UNKNOWN");
    assertThat(outboxRows(result.transferId())).isZero();

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(externalStatus(result.transferId())).isEqualTo("COMPLETED");
    assertThat(outboxRows(result.transferId())).isEqualTo(1);
    assertThat(outboxPayload(result.transferId())).contains("\"status\":\"COMPLETED\"");
    assertThat(balance()).isEqualByComparingTo("876.55");
  }

  @Test
  void timeoutThenRejectGoesUnknownAndIsRefundedByTheLateOutcome() {
    simulator.setMode(SimulatedClearingModeEnum.TIMEOUT_THEN_REJECT);

    TransferResultDto result = externalTransfer("123.45");

    assertThat(result.transferStatus()).isEqualTo("UNKNOWN");
    assertThat(externalStatus(result.transferId())).isEqualTo("UNKNOWN");
    assertThat(balance()).isEqualByComparingTo("876.55");
    assertThat(outboxRows(result.transferId())).isZero();

    int applied = processClearingOutcomes.processOutcomes();

    assertThat(applied).isEqualTo(1);
    assertThat(externalStatus(result.transferId())).isEqualTo("REJECTED");
    assertThat(externalReason(result.transferId())).isEqualTo(SimulatedClearingHouseOutPortImpl.REJECT_REASON);
    assertThat(balance()).isEqualByComparingTo("1000");
    assertThat(outboxRows(result.transferId())).isEqualTo(1);
    assertThat(outboxPayload(result.transferId())).contains("\"status\":\"REJECTED\"");
  }
}
