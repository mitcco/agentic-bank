package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.application.port.out.NotificationOutPort;
import com.bank.transfer.application.port.out.TransferRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aggiunta approvata di transfer-14: il conto che sparisce tra il controllo (findByIban) e il lock
 * (findByIbanForUpdate) da' un AccountNotFoundException con messaggio fisso, senza IBAN.
 * Il repository e' un fake scritto a mano: findByIban risponde presente, findByIbanForUpdate vuoto per gli
 * IBAN "spariti".
 */
class CreateTransferServiceNotFoundMessageTest {

  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0542811101000000000002";
  private static final Iban SOURCE = new Iban(SOURCE_IBAN);
  private static final Iban DEST = new Iban(DEST_IBAN);
  private static final String MESSAGE = "Account not found";

  /** Accounts that exist for findByIban but are gone when locked. */
  private final Set<Iban> vanishedWhenLocked = new HashSet<>();
  private final Map<Iban, Account> accounts = new HashMap<>();
  private final RecordingMetricsOutPort metrics = new RecordingMetricsOutPort();
  private CreateTransferServiceImpl service;

  private final class VanishingAccountRepository implements AccountRepositoryOutPort {

    @Override
    public Optional<Account> findByIban(Iban iban) {
      return Optional.ofNullable(accounts.get(iban));
    }

    @Override
    public Optional<Account> findByIbanForUpdate(Iban iban) {
      if (vanishedWhenLocked.contains(iban)) {
        return Optional.empty();
      }
      return Optional.ofNullable(accounts.get(iban));
    }

    @Override
    public Account save(Account account) {
      accounts.put(account.iban(), account);
      return account;
    }
  }

  @BeforeEach
  void setUp() {
    accounts.put(SOURCE, new Account("acc-1", SOURCE, new Money(new BigDecimal("100"), "EUR")));
    accounts.put(DEST, new Account("acc-2", DEST, new Money(new BigDecimal("50"), "EUR")));
    FraudCheckOutPort allowed = (transferId, source, dest, amount, currency) ->
      new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
    service = new CreateTransferServiceImpl(new VanishingAccountRepository(), mock(TransferRepositoryOutPort.class),
      allowed, mock(NotificationOutPort.class), new TransactionTemplate(mock(PlatformTransactionManager.class)),
      metrics);
  }

  private CreateTransferCommand command() {
    return new CreateTransferCommand(SOURCE_IBAN, DEST_IBAN, new BigDecimal("30"), "EUR");
  }

  @Test
  void sourceVanishedBetweenTheCheckAndTheLockGivesAFixedMessageWithoutIban() {
    vanishedWhenLocked.add(SOURCE);

    assertThatThrownBy(() -> service.executeTransfer(command()))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage(MESSAGE)
      .hasMessageNotContaining(SOURCE_IBAN)
      .hasMessageNotContaining(DEST_IBAN);
  }

  @Test
  void destinationVanishedBetweenTheCheckAndTheLockGivesAFixedMessageWithoutIban() {
    vanishedWhenLocked.add(DEST);

    assertThatThrownBy(() -> service.executeTransfer(command()))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage(MESSAGE)
      .hasMessageNotContaining(SOURCE_IBAN)
      .hasMessageNotContaining(DEST_IBAN);
  }

  @Test
  void bothVanishedGiveTheSameFixedMessage() {
    vanishedWhenLocked.add(SOURCE);
    vanishedWhenLocked.add(DEST);

    assertThatThrownBy(() -> service.executeTransfer(command()))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage(MESSAGE);
  }

  @Test
  void theMessageHasNoIbanEvenWhenTheIbanIsWrittenWithSpacesAndLowerCase() {
    vanishedWhenLocked.add(DEST);

    assertThatThrownBy(() -> service.executeTransfer(
      new CreateTransferCommand("it60 x054 2811 1010 0000 0000 001", "it60 x054 2811 1010 0000 0000 002",
        new BigDecimal("30"), "EUR")))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage(MESSAGE)
      .hasMessageNotContaining("0542811101")
      .hasMessageNotContaining("IT60");
  }

  @Test
  void aVanishedAccountCountsNothingInTheMetrics() {
    vanishedWhenLocked.add(DEST);

    assertThatThrownBy(() -> service.executeTransfer(command())).isInstanceOf(AccountNotFoundException.class);

    assertThat(metrics.calls()).isEmpty();
  }

  @Test
  void theMessagesOfTheMissingAccountsBeforeTheLockStayAsTheyAre() {
    accounts.remove(SOURCE);
    assertThatThrownBy(() -> service.executeTransfer(command()))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage("Source Account not found");

    accounts.put(SOURCE, new Account("acc-1", SOURCE, new Money(new BigDecimal("100"), "EUR")));
    accounts.remove(DEST);
    assertThatThrownBy(() -> service.executeTransfer(command()))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage("Destination Account not found");
  }
}
