package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.command.ListTransfersCommand;
import com.bank.transfer.application.dto.StoredTransferListDto;
import com.bank.transfer.application.dto.StoredTransferRowDto;
import com.bank.transfer.application.dto.TransferListCriteria;
import com.bank.transfer.application.dto.TransferPageDto;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.exception.StoredTransferDataException;
import com.bank.transfer.application.port.out.TransferListQueryOutPort;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Rows already stored that cannot be shown: the whole page is refused with an explicit, fixed error. */
class ListTransfersServiceImplStoredDataTest {

  private static final String IBAN = "IT60X0542811101000000000102";
  private static final String OTHER_IBAN = "IT60X0306909606100000012345";
  private static final String FIXED_MESSAGE = "Stored transfer data is not valid";
  private static final Instant CREATED_AT = Instant.parse("2026-10-19T08:30:00Z");

  private final List<TransferListCriteria> receivedCriteria = new ArrayList<>();

  private ListTransfersServiceImpl serviceReturning(StoredTransferRowDto... rows) {
    TransferListQueryOutPort port = criteria -> {
      receivedCriteria.add(criteria);
      return new StoredTransferListDto(List.of(rows), rows.length);
    };
    return new ListTransfersServiceImpl(port);
  }

  private static ListTransfersCommand onlyIban(String iban) {
    return new ListTransfersCommand(iban, null, null, null, null, null, null, null);
  }

  private static StoredTransferRowDto row(String id, String source, String dest, String amount) {
    return new StoredTransferRowDto(id, TransferTypeEnum.EXTERNAL, source, dest, new BigDecimal(amount), "EUR",
      TransferStatusEnum.COMPLETED, CREATED_AT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"10.5001", "10.1234", "10.0001", "10.0050", "0.0099"})
  void amountNotRepresentableWithTwoDecimalsRefusesTheWholeResponse(String amount) {
    ListTransfersServiceImpl service = serviceReturning(row("t-1", IBAN, OTHER_IBAN, amount));

    assertThatThrownBy(() -> service.listTransfers(onlyIban(IBAN)))
      .isInstanceOf(StoredTransferDataException.class)
      .isNotInstanceOf(IllegalArgumentException.class)
      .isNotInstanceOf(ArithmeticException.class)
      .hasMessage(FIXED_MESSAGE)
      .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {"10.5000", "10.50", "10", "100.2500"})
  void amountWithZerosBeyondTheSecondDecimalStaysValid(String amount) {
    TransferPageDto page = serviceReturning(row("t-1", IBAN, OTHER_IBAN, amount)).listTransfers(onlyIban(IBAN));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).amount()).isEqualByComparingTo(amount);
    assertThat(page.items().get(0).amount().scale()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(strings = {"IT60X030690960610000001234", "IT60X0306909606100000012$45", ""})
  void invalidStoredDestIbanRefusesTheWholeResponse(String badDest) {
    ListTransfersServiceImpl service = serviceReturning(row("t-1", IBAN, badDest, "10.50"));

    assertThatThrownBy(() -> service.listTransfers(onlyIban(IBAN)))
      .isInstanceOf(StoredTransferDataException.class)
      .isNotInstanceOf(IllegalArgumentException.class)
      .hasMessage(FIXED_MESSAGE)
      .hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {"IT60X030690960610000001234", "IT60X0306909606100000012$45", ""})
  void invalidStoredSourceIbanOfAnIncomingRowRefusesTheWholeResponse(String badSource) {
    ListTransfersServiceImpl service = serviceReturning(row("t-1", badSource, IBAN, "10.50"));

    assertThatThrownBy(() -> service.listTransfers(onlyIban(IBAN)))
      .isInstanceOf(StoredTransferDataException.class)
      .isNotInstanceOf(IllegalArgumentException.class)
      .hasMessage(FIXED_MESSAGE)
      .hasNoCause();
  }

  @Test
  void theErrorNeverContainsTheAmountTheIbansNorTheId() {
    String badDest = "IT60X030690960610000001234";
    ListTransfersServiceImpl badAmount = serviceReturning(row("t-1", IBAN, OTHER_IBAN, "10.1234"));
    ListTransfersServiceImpl badIban = serviceReturning(row("t-1", IBAN, badDest, "10.50"));

    assertThatThrownBy(() -> badAmount.listTransfers(onlyIban(IBAN)))
      .hasMessageNotContaining("10.1234")
      .hasMessageNotContaining("10.12")
      .hasMessageNotContaining(IBAN)
      .hasMessageNotContaining(OTHER_IBAN)
      .hasMessageNotContaining("t-1");
    assertThatThrownBy(() -> badIban.listTransfers(onlyIban(IBAN)))
      .hasMessageNotContaining(badDest)
      .hasMessageNotContaining("IT60")
      .hasMessageNotContaining("10.50")
      .hasMessageNotContaining("t-1");
  }

  @Test
  void oneCorruptRowAmongValidOnesRefusesTheWholePage() {
    ListTransfersServiceImpl service = serviceReturning(
      row("t-1", IBAN, OTHER_IBAN, "10.50"),
      row("t-2", IBAN, OTHER_IBAN, "10.1234"),
      row("t-3", OTHER_IBAN, IBAN, "30.00"));

    assertThatThrownBy(() -> service.listTransfers(onlyIban(IBAN)))
      .isInstanceOf(StoredTransferDataException.class)
      .hasMessage(FIXED_MESSAGE);
  }

  @Test
  void validRowsOnlyStillProduceTheWholePage() {
    ListTransfersServiceImpl service = serviceReturning(
      row("t-1", IBAN, OTHER_IBAN, "10.50"),
      row("t-2", OTHER_IBAN, IBAN, "20.00"));

    TransferPageDto page = service.listTransfers(onlyIban(IBAN));

    assertThat(page.items()).hasSize(2);
  }

  @Test
  void invalidRequestParametersStayIllegalArgumentsWithTheFixedMessages() {
    ListTransfersServiceImpl service = serviceReturning(row("t-1", IBAN, OTHER_IBAN, "10.1234"));

    assertThatThrownBy(() -> service.listTransfers(
      new ListTransfersCommand(IBAN, "SENT", null, null, null, null, null, null)))
      .isInstanceOf(IllegalArgumentException.class)
      .isNotInstanceOf(StoredTransferDataException.class)
      .hasMessage("direction must be ALL, OUTGOING or INCOMING");
    assertThatThrownBy(() -> service.listTransfers(
      new ListTransfersCommand(IBAN, null, null, null, null, null, null, "101")))
      .isInstanceOf(IllegalArgumentException.class)
      .isNotInstanceOf(StoredTransferDataException.class)
      .hasMessage("size must be between 1 and 100");
    assertThatThrownBy(() -> service.listTransfers(onlyIban("IT60X05428")))
      .isInstanceOf(IllegalArgumentException.class)
      .isNotInstanceOf(StoredTransferDataException.class)
      .hasMessage("Malformed IBAN, it has to be 27 characters");
    assertThat(receivedCriteria).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", " ", "   "})
  void aNullOrBlankIbanIsRequired(String iban) {
    ListTransfersServiceImpl service = serviceReturning();

    assertThatThrownBy(() -> service.listTransfers(onlyIban(iban)))
      .isInstanceOf(IllegalArgumentException.class)
      .isNotInstanceOf(StoredTransferDataException.class)
      .hasMessage("iban is required");
    assertThat(receivedCriteria).isEmpty();
  }
}
