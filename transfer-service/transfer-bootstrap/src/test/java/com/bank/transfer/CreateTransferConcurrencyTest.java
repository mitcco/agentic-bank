package com.bank.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import com.bank.transfer.domain.exception.InsufficientFundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Real service, real JPA adapters and a real (in-memory H2) database: the pessimistic lock can only be proven here.
 * Every test uses its own accounts, so the tests do not depend on each other.
 */
@SpringBootTest(properties = {
  "spring.datasource.url=jdbc:h2:mem:concurrency;DB_CLOSE_DELAY=-1",
  "spring.datasource.username=sa",
  "spring.datasource.password="
})
class CreateTransferConcurrencyTest {

  /** The fraud check is not what is tested here: it always allows, from any thread, without any network. */
  @TestConfiguration
  static class AlwaysAllowedFraudCheck {

    @Bean
    @Primary
    FraudCheckOutPort alwaysAllowedFraudCheck() {
      return (transferId, sourceIban, destIban, amount, currency) ->
        new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
    }
  }

  private static final String IBAN_PREFIX = "IT60X0542811101000000";

  @Autowired
  private CreateTransferInPort createTransfer;

  @Autowired
  private AccountRepositoryOutPort accountRepository;

  private static String iban(String suffix) {
    return IBAN_PREFIX + suffix;
  }

  private void createAccount(String suffix, String balance) {
    accountRepository.save(
      new Account("acc-" + suffix, new Iban(iban(suffix)), new Money(new BigDecimal(balance), "EUR")));
  }

  private BigDecimal balanceOf(String suffix) {
    return accountRepository.findByIban(new Iban(iban(suffix))).orElseThrow().money().amount();
  }

  /** @return true if the transfer went through, false if it was refused for insufficient funds */
  private Callable<Boolean> transfer(String fromSuffix, String toSuffix, String amount) {
    return () -> {
      try {
        createTransfer.executeTransfer(
          new CreateTransferCommand(iban(fromSuffix), iban(toSuffix), new BigDecimal(amount), "EUR"));
        return true;
      } catch (InsufficientFundException e) {
        return false;
      }
    };
  }

  /** Starts all the tasks together and waits for them; any other exception makes the test fail. */
  private List<Boolean> runTogether(List<Callable<Boolean>> tasks) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Boolean>> futures = new ArrayList<>();
      for (Callable<Boolean> task : tasks) {
        futures.add(pool.submit(() -> {
          start.await();
          return task.call();
        }));
      }
      start.countDown();

      List<Boolean> results = new ArrayList<>();
      for (Future<Boolean> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static long successes(List<Boolean> results) {
    return results.stream().filter(Boolean::booleanValue).count();
  }

  @Test
  void twoParallelTransfersCannotSpendTheSameMoneyTwice() throws Exception {
    createAccount("P00001", "100");
    createAccount("P00002", "0");

    List<Boolean> results = runTogether(List.of(
      transfer("P00001", "P00002", "100"),
      transfer("P00001", "P00002", "100")));

    assertThat(successes(results)).isEqualTo(1);
    assertThat(balanceOf("P00001")).isEqualByComparingTo("0");
    assertThat(balanceOf("P00002")).isEqualByComparingTo("100");
  }

  @Test
  void manyParallelTransfersNeverSpendMoreThanTheBalance() throws Exception {
    createAccount("P00003", "1000");
    createAccount("P00004", "0");

    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      tasks.add(transfer("P00003", "P00004", "100"));
    }
    List<Boolean> results = runTogether(tasks);

    // the balance covers exactly 10 transfers of 100: the other 10 are refused
    assertThat(successes(results)).isEqualTo(10);
    assertThat(balanceOf("P00003")).isEqualByComparingTo("0");
    assertThat(balanceOf("P00004")).isEqualByComparingTo("1000");
  }

  @Test
  void transfersInOppositeDirectionsDoNotDeadlockAndKeepTheTotal() throws Exception {
    createAccount("P00005", "500");
    createAccount("P00006", "500");

    List<Callable<Boolean>> tasks = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      tasks.add(transfer("P00005", "P00006", "10"));
      tasks.add(transfer("P00006", "P00005", "10"));
    }
    List<Boolean> results = runTogether(tasks);

    assertThat(successes(results)).isEqualTo(20);
    assertThat(balanceOf("P00005")).isEqualByComparingTo("500");
    assertThat(balanceOf("P00006")).isEqualByComparingTo("500");
  }
}
