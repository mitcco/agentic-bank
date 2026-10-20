package com.bank.transfer.rest.servicedto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.dto.TransferDetailsDto;
import com.bank.transfer.application.exception.TransferNotFoundException;
import com.bank.transfer.application.port.in.GetTransferInPort;
import com.bank.transfer.rest.dto.response.TransferDetailsResponseDto;
import com.bank.transfer.rest.mapper.TransferDetailsDtoToTransferDetailsResponseDtoMapperImpl;
import com.bank.transfer.rest.servicedto.TransferQueryServiceDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferQueryServiceDtoImplTest {

  private static final String SOURCE_MASKED = "IT60" + "*".repeat(19) + "0001";
  private static final String DEST_MASKED = "IT60" + "*".repeat(19) + "0149";

  private final List<String> receivedIds = new ArrayList<>();

  private TransferQueryServiceDto serviceWith(GetTransferInPort port) {
    return new TransferQueryServiceDtoImpl(port, new TransferDetailsDtoToTransferDetailsResponseDtoMapperImpl());
  }

  @Test
  void mapsTheResultOfThePortKeepingTheMaskedValuesAndRenamingTheStatus() {
    GetTransferInPort port = id -> {
      receivedIds.add(id);
      return new TransferDetailsDto("t-1", SOURCE_MASKED, DEST_MASKED, new BigDecimal("100.25"), "EUR",
          "REJECTED", List.of("a", "b"));
    };

    TransferDetailsResponseDto response = serviceWith(port).getTransfer("t-1");

    assertThat(receivedIds).containsExactly("t-1");
    assertThat(response.transferId()).isEqualTo("t-1");
    assertThat(response.sourceIbanMasked()).isEqualTo(SOURCE_MASKED);
    assertThat(response.destIbanMasked()).isEqualTo(DEST_MASKED);
    assertThat(response.amount()).isEqualByComparingTo("100.25");
    assertThat(response.currency()).isEqualTo("EUR");
    assertThat(response.transferStatus()).isEqualTo("REJECTED");
    assertThat(response.reasons()).containsExactly("a", "b");
  }

  @Test
  void aTransferWithoutReasonsHasAnEmptyList() {
    GetTransferInPort port = id -> new TransferDetailsDto("t-2", SOURCE_MASKED, DEST_MASKED,
        new BigDecimal("10.00"), "EUR", "COMPLETED", List.of());

    TransferDetailsResponseDto response = serviceWith(port).getTransfer("t-2");

    assertThat(response.transferStatus()).isEqualTo("COMPLETED");
    assertThat(response.reasons()).isNotNull().isEmpty();
  }

  @Test
  void transferNotFoundIsPropagatedAsItIs() {
    TransferNotFoundException notFound = new TransferNotFoundException();
    GetTransferInPort port = id -> {
      throw notFound;
    };

    assertThatThrownBy(() -> serviceWith(port).getTransfer("missing")).isSameAs(notFound);
  }
}
