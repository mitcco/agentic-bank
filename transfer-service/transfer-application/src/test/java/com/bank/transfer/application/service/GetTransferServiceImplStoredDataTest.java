package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.StoredTransferDto;
import com.bank.transfer.application.dto.TransferDetailsDto;
import com.bank.transfer.application.exception.StoredTransferDataException;
import com.bank.transfer.application.exception.TransferNotFoundException;
import com.bank.transfer.application.port.out.TransferQueryOutPort;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Rows already stored that cannot be shown: the service refuses them with an explicit, fixed error. */
class GetTransferServiceImplStoredDataTest {

  private static final String VALID_SOURCE = "IT60X0542811101000000000001";
  private static final String VALID_DEST = "IT60X0306909606100000012345";
  private static final String FIXED_MESSAGE = "Stored transfer data is not valid";

  private final List<String> lookedUpIds = new ArrayList<>();

  private GetTransferServiceImpl serviceReturning(StoredTransferDto stored) {
    TransferQueryOutPort port = id -> {
      lookedUpIds.add(id);
      return Optional.ofNullable(stored);
    };
    return new GetTransferServiceImpl(port);
  }

  private static StoredTransferDto stored(String sourceIban, String destIban, String amount) {
    return new StoredTransferDto("t-1", sourceIban, destIban, new BigDecimal(amount), "EUR",
      TransferStatusEnum.COMPLETED, null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"10.5001", "10.1234", "10.0001", "10.0050", "0.0099", "10.5199"})
  void amountNotRepresentableWithTwoDecimalsIsRefused(String amount) {
    GetTransferServiceImpl service = serviceReturning(stored(VALID_SOURCE, VALID_DEST, amount));

    assertThatThrownBy(() -> service.getTransfer("t-1"))
      .isInstanceOf(StoredTransferDataException.class)
      .isNotInstanceOf(IllegalArgumentException.class)
      .isNotInstanceOf(ArithmeticException.class)
      .hasMessage(FIXED_MESSAGE)
      .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {"10.5000", "10.50", "10.5", "10", "100.2500", "0.0000"})
  void amountWithZeroBeyondTheSecondDecimalStaysValid(String amount) {
    TransferDetailsDto details = serviceReturning(stored(VALID_SOURCE, VALID_DEST, amount)).getTransfer("t-1");

    assertThat(details.amount()).isEqualByComparingTo(amount);
    assertThat(details.amount().scale()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "IT60X054281110100000000000",
    "IT60X05428111010000000000011",
    "IT60X0542811101000000000$01",
    "",
    "not an iban"
  })
  void invalidStoredSourceIbanIsRefusedWithAnExplicitError(String sourceIban) {
    GetTransferServiceImpl service = serviceReturning(stored(sourceIban, VALID_DEST, "10.50"));

    assertThatThrownBy(() -> service.getTransfer("t-1"))
      .isInstanceOf(StoredTransferDataException.class)
      .isNotInstanceOf(IllegalArgumentException.class)
      .hasMessage(FIXED_MESSAGE)
      .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "IT60X030690960610000001234",
    "IT60X03069096061000000123456",
    "IT60X0306909606100000012$45",
    "",
    "not an iban"
  })
  void invalidStoredDestIbanIsRefusedWithAnExplicitError(String destIban) {
    GetTransferServiceImpl service = serviceReturning(stored(VALID_SOURCE, destIban, "10.50"));

    assertThatThrownBy(() -> service.getTransfer("t-1"))
      .isInstanceOf(StoredTransferDataException.class)
      .isNotInstanceOf(IllegalArgumentException.class)
      .hasMessage(FIXED_MESSAGE)
      .hasNoCause();
  }

  @Test
  void theErrorNeverContainsTheAmountTheIbansNorTheId() {
    String badSource = "IT60X054281110100000000000";
    GetTransferServiceImpl badAmount = serviceReturning(stored(VALID_SOURCE, VALID_DEST, "10.1234"));
    GetTransferServiceImpl badIban = serviceReturning(stored(badSource, VALID_DEST, "10.50"));

    assertThatThrownBy(() -> badAmount.getTransfer("t-1"))
      .hasMessageNotContaining("10.1234")
      .hasMessageNotContaining("10.12")
      .hasMessageNotContaining(VALID_SOURCE)
      .hasMessageNotContaining(VALID_DEST)
      .hasMessageNotContaining("t-1");
    assertThatThrownBy(() -> badIban.getTransfer("t-1"))
      .hasMessageNotContaining(badSource)
      .hasMessageNotContaining("IT60")
      .hasMessageNotContaining("10.50")
      .hasMessageNotContaining("t-1");
  }

  @Test
  void blankIdIsStillAnIllegalArgument() {
    GetTransferServiceImpl service = serviceReturning(stored(VALID_SOURCE, VALID_DEST, "10.50"));

    assertThatThrownBy(() -> service.getTransfer("  "))
      .isInstanceOf(IllegalArgumentException.class)
      .isNotInstanceOf(StoredTransferDataException.class);
    assertThat(lookedUpIds).isEmpty();
  }

  @Test
  void missingTransferIsStillNotFound() {
    GetTransferServiceImpl service = serviceReturning(null);

    assertThatThrownBy(() -> service.getTransfer("missing"))
      .isInstanceOf(TransferNotFoundException.class)
      .isNotInstanceOf(StoredTransferDataException.class)
      .hasMessage("Transfer not found");
  }
}
