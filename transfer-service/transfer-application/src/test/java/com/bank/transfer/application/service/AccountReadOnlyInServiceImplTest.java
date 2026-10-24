package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.AccountDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.mapper.AccountToAccountDtoMapperImpl;
import com.bank.transfer.application.port.out.AccountRepositoryOutPort;
import com.bank.transfer.domain.Account;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Repository fake written by hand; the real generated MapStruct mapper is used. */
class AccountReadOnlyInServiceImplTest {

  private static final String IBAN = "IT60X0542811101000000000001";
  private static final String MESSAGE = "Account not found";

  private final Map<Iban, Account> accounts = new HashMap<>();
  private final List<Iban> lookedUp = new ArrayList<>();

  private final AccountRepositoryOutPort repository = new AccountRepositoryOutPort() {
    @Override
    public Optional<Account> findByIban(Iban iban) {
      lookedUp.add(iban);
      return Optional.ofNullable(accounts.get(iban));
    }

    @Override
    public Optional<Account> findByIbanForUpdate(Iban iban) {
      throw new UnsupportedOperationException("read-only service must not lock");
    }

    @Override
    public Account save(Account account) {
      throw new UnsupportedOperationException("read-only service must not save");
    }
  };

  private final AccountReadOnlyInServiceImpl service =
    new AccountReadOnlyInServiceImpl(repository, new AccountToAccountDtoMapperImpl());

  @Test
  void existingAccountIsReturnedAsDto() {
    accounts.put(new Iban(IBAN), new Account("acc-1", new Iban(IBAN), new Money(new BigDecimal("125.50"), "EUR")));

    AccountDto dto = service.getAccount(IBAN);

    assertThat(dto.iban()).isEqualTo(IBAN);
    assertThat(dto.amount()).isEqualByComparingTo("125.50");
    assertThat(dto.currency()).isEqualTo("EUR");
    assertThat(lookedUp).containsExactly(new Iban(IBAN));
  }

  @Test
  void existingAccountIsFoundEvenWhenTheIbanIsWrittenWithSpacesAndLowerCase() {
    accounts.put(new Iban(IBAN), new Account("acc-1", new Iban(IBAN), new Money(new BigDecimal("10"), "EUR")));

    AccountDto dto = service.getAccount("it60 x054 2811 1010 0000 0000 001");

    assertThat(dto.iban()).isEqualTo(IBAN);
  }

  @Test
  void missingAccountGivesAFixedMessageWithoutIban() {
    assertThatThrownBy(() -> service.getAccount(IBAN))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage(MESSAGE)
      .hasMessageNotContaining(IBAN);
  }

  @Test
  void missingAccountMessageHasNoIbanEvenWhenWrittenWithSpacesAndLowerCase() {
    String raw = "it60 x054 2811 1010 0000 0000 001";

    assertThatThrownBy(() -> service.getAccount(raw))
      .isInstanceOf(AccountNotFoundException.class)
      .hasMessage(MESSAGE)
      .hasMessageNotContaining(raw)
      .hasMessageNotContaining(IBAN)
      .hasMessageNotContaining("0542811101");
  }

  @Test
  void malformedIbanIsRejectedWithTheIbanValidationErrorWithoutTheIbanInTheMessage() {
    assertThatThrownBy(() -> service.getAccount("IT60"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Malformed IBAN, it has to be 27 characters");
    assertThat(lookedUp).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"IT60X05428111010000000000!!", "it60x05428111010000000000!!"})
  void ibanWithInvalidCharactersIsRejectedWithoutRepeatingIt(String raw) {
    assertThatThrownBy(() -> service.getAccount(raw))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageNotContaining("0542811101");
    assertThat(lookedUp).isEmpty();
  }
}
