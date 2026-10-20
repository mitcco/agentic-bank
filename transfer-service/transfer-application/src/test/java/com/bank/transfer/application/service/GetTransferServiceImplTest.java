package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.StoredTransferDto;
import com.bank.transfer.application.dto.TransferDetailsDto;
import com.bank.transfer.application.exception.TransferNotFoundException;
import com.bank.transfer.application.port.out.TransferQueryOutPort;
import com.bank.transfer.domain.TransferStatusEnum;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The out port has a single method, so a lambda is enough. IBANs come in whole and go out masked. */
class GetTransferServiceImplTest {

  private static final String SOURCE_IBAN = "IT60X0542811101000000000001";
  private static final String DEST_IBAN = "IT60X0306909606100000012345";
  private static final String SOURCE_MASKED = "IT60" + "*".repeat(19) + "0001";
  private static final String DEST_MASKED = "IT60" + "*".repeat(19) + "2345";

  private final List<String> lookedUpIds = new ArrayList<>();

  private GetTransferServiceImpl serviceReturning(StoredTransferDto stored) {
    TransferQueryOutPort port = id -> {
      lookedUpIds.add(id);
      return Optional.ofNullable(stored);
    };
    return new GetTransferServiceImpl(port);
  }

  private static StoredTransferDto stored(TransferStatusEnum status, String reason) {
    return new StoredTransferDto("t-1", SOURCE_IBAN, DEST_IBAN, new BigDecimal("100.2500"), "EUR", status, reason);
  }

  @Test
  void internalCompletedHasNoReasons() {
    TransferDetailsDto details = serviceReturning(stored(TransferStatusEnum.COMPLETED, null)).getTransfer("t-1");

    assertThat(details.transferId()).isEqualTo("t-1");
    assertThat(details.status()).isEqualTo("COMPLETED");
    assertThat(details.reasons()).isEmpty();
    assertThat(details.sourceIbanMasked()).isEqualTo(SOURCE_MASKED);
    assertThat(details.destIbanMasked()).isEqualTo(DEST_MASKED);
    assertThat(details.amount()).isEqualByComparingTo("100.25");
    assertThat(details.currency()).isEqualTo("EUR");
    assertThat(lookedUpIds).containsExactly("t-1");
  }

  @Test
  void maskedIbansNeverContainTheMiddleOfTheOriginals() {
    TransferDetailsDto details = serviceReturning(stored(TransferStatusEnum.COMPLETED, null)).getTransfer("t-1");

    assertThat(details.sourceIbanMasked())
      .doesNotContain(SOURCE_IBAN.substring(4, 23))
      .doesNotContain("05428");
    assertThat(details.destIbanMasked())
      .doesNotContain(DEST_IBAN.substring(4, 23))
      .doesNotContain("03069");
    assertThat(details.toString()).doesNotContain(SOURCE_IBAN).doesNotContain(DEST_IBAN);
  }

  @Test
  void amountIsReturnedWithTwoDecimals() {
    StoredTransferDto tenAndAHalf = new StoredTransferDto("t-1", SOURCE_IBAN, DEST_IBAN, new BigDecimal("10.5000"),
      "EUR", TransferStatusEnum.COMPLETED, null);

    TransferDetailsDto details = serviceReturning(tenAndAHalf).getTransfer("t-1");

    assertThat(details.amount()).isEqualByComparingTo("10.50");
    assertThat(details.amount().scale()).isEqualTo(2);
  }

  @Test
  void internalRejectedHasNoReasonsBecauseTheyAreNotStored() {
    TransferDetailsDto details = serviceReturning(stored(TransferStatusEnum.REJECTED, null)).getTransfer("t-1");

    assertThat(details.status()).isEqualTo("REJECTED");
    assertThat(details.reasons()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(value = TransferStatusEnum.class, names = {"SENT", "UNKNOWN"})
  void externalSentAndUnknownHaveNoReasons(TransferStatusEnum status) {
    TransferDetailsDto details = serviceReturning(stored(status, null)).getTransfer("t-1");

    assertThat(details.status()).isEqualTo(status.name());
    assertThat(details.reasons()).isEmpty();
  }

  @Test
  void externalRejectedReasonBecomesAOneElementList() {
    StoredTransferDto rejected = stored(TransferStatusEnum.REJECTED, "Rejected by the clearing house");

    TransferDetailsDto details = serviceReturning(rejected).getTransfer("t-1");

    assertThat(details.status()).isEqualTo("REJECTED");
    assertThat(details.reasons()).containsExactly("Rejected by the clearing house");
  }

  @Test
  void joinedFraudReasonsAreSplitBack() {
    TransferDetailsDto details = serviceReturning(stored(TransferStatusEnum.REJECTED, "A; B")).getTransfer("t-1");

    assertThat(details.reasons()).containsExactly("A", "B");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   "})
  void blankReasonIsAnEmptyList(String reason) {
    TransferDetailsDto details = serviceReturning(stored(TransferStatusEnum.REJECTED, reason)).getTransfer("t-1");

    assertThat(details.reasons()).isEmpty();
  }

  @Test
  void missingTransferIsNotFound() {
    GetTransferServiceImpl service = serviceReturning(null);

    assertThatThrownBy(() -> service.getTransfer("missing"))
      .isInstanceOf(TransferNotFoundException.class)
      .hasMessage("Transfer not found");
    assertThat(lookedUpIds).containsExactly("missing");
  }

  @Test
  void nullIdIsRefusedBeforeTheLookup() {
    GetTransferServiceImpl service = serviceReturning(stored(TransferStatusEnum.COMPLETED, null));

    assertThatThrownBy(() -> service.getTransfer(null)).isInstanceOf(NullPointerException.class);
    assertThat(lookedUpIds).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   ", "\t"})
  void blankIdIsRefusedBeforeTheLookup(String id) {
    GetTransferServiceImpl service = serviceReturning(stored(TransferStatusEnum.COMPLETED, null));

    assertThatThrownBy(() -> service.getTransfer(id)).isInstanceOf(IllegalArgumentException.class);
    assertThat(lookedUpIds).isEmpty();
  }

  @Test
  void aNotFoundIsNotAnIllegalArgument() {
    GetTransferServiceImpl service = serviceReturning(null);

    assertThatThrownBy(() -> service.getTransfer("missing"))
      .isNotInstanceOf(IllegalArgumentException.class);
  }
}
