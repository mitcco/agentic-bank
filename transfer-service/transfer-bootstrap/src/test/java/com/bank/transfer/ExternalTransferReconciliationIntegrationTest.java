package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.port.in.CreateExternalTransferInPort;
import com.bank.transfer.application.port.in.ProcessClearingOutcomesInPort;
import com.bank.transfer.application.port.in.ReconcileExternalTransfersInPort;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.ExternalTransferRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingHouseOutPortImpl;
import com.bank.transfer.clearing.port.out.clearing.impl.SimulatedClearingModeEnum;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.Transfer;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The reconciliation of the stale external transfers with the real service, the real JPA adapters, the simulated
 * clearing house and a dedicated in-memory H2 database. The schedulers are off (the default): the test calls the
 * ports in by hand, with a threshold in the future (everything is stale) or in the past (nothing is).
 * Every check is by transfer id: the context may hold other SENT and UNKNOWN rows.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:reconciliation;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class ExternalTransferReconciliationIntegrationTest {

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
  private static final String NOT_FOUND_REASON = "Instruction not found at the clearing house";
  private static final int MAX = 100;
  private static final AtomicInteger SEQUENCE = new AtomicInteger();

  @Autowired
  private CreateExternalTransferInPort createExternalTransfer;

  @Autowired
  private ReconcileExternalTransfersInPort reconcileExternalTransfers;

  @Autowired
  private ProcessClearingOutcomesInPort processClearingOutcomes;

  @Autowired
  private SimulatedClearingHouseOutPortImpl simulator;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  @Autowired
  private ExternalTransferRepositoryOutPort externalTransferRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private String sourceIban;

  @BeforeEach
  void setUp() {
    // the simulator is a singleton shared by the tests: back to ACCEPT and with nothing left in its queue
    simulator.setMode(SimulatedClearingModeEnum.ACCEPT);
    simulator.pollOutcomes();

    int number = SEQUENCE.incrementAndGet();
    sourceIban = IBAN_PREFIX + String.format("H%05d", number);
    accountRepository.save(
      new Account("acc-reconciliation-" + number, new Iban(sourceIban), new Money(new BigDecimal("1000"), "EUR")));
  }

  // ------------------------------------------------------------------ helpers

  private TransferResultDto externalTransfer(String amount) {
    return createExternalTransfer.executeExternalTransfer(
      new CreateTransferCommand(sourceIban, EXTERNAL_IBAN, new BigDecimal(amount), "EUR"));
  }

  /** A transfer debited and saved as SENT with the ports, but never submitted to the clearing house. */
  private String sentButNeverSubmitted(String amount) {
    Money money = new Money(new BigDecimal(amount), "EUR");
    Account account = accountRepository.findByIban(new Iban(sourceIban)).orElseThrow();
    accountRepository.save(account.charge(money));
    Transfer sent = Transfer.create(new Iban(sourceIban), new Iban(EXTERNAL_IBAN), money).markSent();
    externalTransferRepository.save(sent, null);
    return sent.id();
  }

  private static Instant everythingIsStale() {
    return Instant.now().plusSeconds(1);
  }

  private static Instant nothingIsStale() {
    return Instant.now().minus(Duration.ofHours(1));
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

  private int outboxRows(String transferId) {
    Integer count = jdbcTemplate.queryForObject("select count(*) from outbox where transfer_id = ?", Integer.class,
      transferId);
    assertThat(count).isNotNull();
    return count;
  }

  private String outboxPayload(String transferId) {
    return jdbcTemplate.queryForObject("select payload from outbox where transfer_id = ?", String.class,
      transferId);
  }

  // ------------------------------------------------------------------ tests

  @Test
  void unknownIsClosedByQueryingTheClearingHouse() {
    simulator.setMode(SimulatedClearingModeEnum.TIMEOUT_THEN_ACCEPT);
    TransferResultDto result = externalTransfer("123.45");
    assertThat(result.transferStatus()).isEqualTo("UNKNOWN");
    assertThat(balance()).isEqualByComparingTo("876.55");
    assertThat(outboxRows(result.transferId())).isZero();

    int reconciled = reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);

    assertThat(reconciled).isGreaterThanOrEqualTo(1);
    assertThat(externalStatus(result.transferId())).isEqualTo("COMPLETED");
    assertThat(outboxRows(result.transferId())).isEqualTo(1);
    assertThat(outboxPayload(result.transferId())).contains("\"status\":\"COMPLETED\"");
    assertThat(balance()).isEqualByComparingTo("876.55");

    // the late outcome is still in the queue of the clearing house: it finds the transfer concluded
    int lateOutcomes = processClearingOutcomes.processOutcomes();

    assertThat(lateOutcomes).isZero();
    assertThat(externalStatus(result.transferId())).isEqualTo("COMPLETED");
    assertThat(outboxRows(result.transferId())).isEqualTo(1);
    assertThat(balance()).isEqualByComparingTo("876.55");
  }

  @Test
  void unknownWithALateRejectionIsRefunded() {
    simulator.setMode(SimulatedClearingModeEnum.TIMEOUT_THEN_REJECT);
    TransferResultDto result = externalTransfer("123.45");
    assertThat(result.transferStatus()).isEqualTo("UNKNOWN");
    assertThat(balance()).isEqualByComparingTo("876.55");

    reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);

    assertThat(externalStatus(result.transferId())).isEqualTo("REJECTED");
    assertThat(externalReason(result.transferId())).isEqualTo(SimulatedClearingHouseOutPortImpl.REJECT_REASON);
    assertThat(balance()).isEqualByComparingTo("1000");
    assertThat(outboxRows(result.transferId())).isEqualTo(1);
    assertThat(outboxPayload(result.transferId())).contains("\"status\":\"REJECTED\"");

    // the late outcome of the queue changes nothing
    assertThat(processClearingOutcomes.processOutcomes()).isZero();
    assertThat(balance()).isEqualByComparingTo("1000");
    assertThat(outboxRows(result.transferId())).isEqualTo(1);
  }

  @Test
  void sentNeverReceivedIsRefunded() {
    String transferId = sentButNeverSubmitted("123.45");
    assertThat(balance()).isEqualByComparingTo("876.55");
    assertThat(externalStatus(transferId)).isEqualTo("SENT");

    reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);

    assertThat(externalStatus(transferId)).isEqualTo("REJECTED");
    assertThat(externalReason(transferId)).isEqualTo(NOT_FOUND_REASON);
    assertThat(balance()).isEqualByComparingTo("1000");
    assertThat(outboxRows(transferId)).isEqualTo(1);
    assertThat(outboxPayload(transferId)).contains("\"status\":\"REJECTED\"");
  }

  @Test
  void freshTransfersAreLeftAlone() {
    simulator.setMode(SimulatedClearingModeEnum.TIMEOUT_THEN_ACCEPT);
    TransferResultDto unknown = externalTransfer("10");
    String sent = sentButNeverSubmitted("20");

    reconcileExternalTransfers.reconcileStale(nothingIsStale(), MAX);

    assertThat(externalStatus(unknown.transferId())).isEqualTo("UNKNOWN");
    assertThat(externalStatus(sent)).isEqualTo("SENT");
    assertThat(balance()).isEqualByComparingTo("970");
    assertThat(outboxRows(unknown.transferId())).isZero();
    assertThat(outboxRows(sent)).isZero();
  }

  @Test
  void secondRoundDoesNotRefundTwice() {
    String transferId = sentButNeverSubmitted("123.45");

    reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);
    reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);

    assertThat(externalStatus(transferId)).isEqualTo("REJECTED");
    assertThat(balance()).isEqualByComparingTo("1000");
    assertThat(outboxRows(transferId)).isEqualTo(1);
  }

  @Test
  void acceptedTransferWaitingInTheQueueIsNotCompletedTwice() {
    TransferResultDto sent = externalTransfer("123.45");
    assertThat(sent.transferStatus()).isEqualTo("SENT");

    reconcileExternalTransfers.reconcileStale(everythingIsStale(), MAX);
    processClearingOutcomes.processOutcomes();

    assertThat(externalStatus(sent.transferId())).isEqualTo("COMPLETED");
    assertThat(outboxRows(sent.transferId())).isEqualTo(1);
    assertThat(balance()).isEqualByComparingTo("876.55");
  }
}
