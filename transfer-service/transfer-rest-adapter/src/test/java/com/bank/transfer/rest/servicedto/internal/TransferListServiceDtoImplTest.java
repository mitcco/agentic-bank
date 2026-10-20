package com.bank.transfer.rest.servicedto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.command.ListTransfersCommand;
import com.bank.transfer.application.dto.TransferListItemDto;
import com.bank.transfer.application.dto.TransferPageDto;
import com.bank.transfer.application.port.in.ListTransfersInPort;
import com.bank.transfer.rest.dto.response.TransferListResponseDto;
import com.bank.transfer.rest.mapper.TransferPageDtoToTransferListResponseDtoMapperImpl;
import com.bank.transfer.rest.servicedto.TransferListServiceDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferListServiceDtoImplTest {

  private static final String IBAN = "IT60X0542811101000000000141";
  private static final String COUNTERPARTY_MASKED = "IT60" + "*".repeat(19) + "0001";

  private final List<ListTransfersCommand> receivedCommands = new ArrayList<>();

  private TransferListServiceDto serviceWith(ListTransfersInPort port) {
    return new TransferListServiceDtoImpl(port, new TransferPageDtoToTransferListResponseDtoMapperImpl());
  }

  private ListTransfersInPort portReturning(TransferPageDto page) {
    return command -> {
      receivedCommands.add(command);
      return page;
    };
  }

  private static TransferPageDto emptyPage() {
    return new TransferPageDto(List.of(), 0, 20, 0, false);
  }

  @Test
  void buildsTheCommandFromTheRawParameters() {
    TransferListServiceDto service = serviceWith(portReturning(emptyPage()));

    service.listTransfers(IBAN, "OUTGOING", List.of("COMPLETED", "REJECTED"), "EXTERNAL", "2026-10-01T00:00:00Z",
      "2026-10-19T00:00:00Z", "2", "10");

    assertThat(receivedCommands).hasSize(1);
    ListTransfersCommand command = receivedCommands.get(0);
    assertThat(command.iban()).isEqualTo(IBAN);
    assertThat(command.direction()).isEqualTo("OUTGOING");
    assertThat(command.statuses()).containsExactly("COMPLETED", "REJECTED");
    assertThat(command.type()).isEqualTo("EXTERNAL");
    assertThat(command.from()).isEqualTo("2026-10-01T00:00:00Z");
    assertThat(command.to()).isEqualTo("2026-10-19T00:00:00Z");
    assertThat(command.page()).isEqualTo("2");
    assertThat(command.size()).isEqualTo("10");
  }

  @Test
  void missingParametersReachThePortAsNullsAndAnEmptyListOfStatuses() {
    TransferListServiceDto service = serviceWith(portReturning(emptyPage()));

    service.listTransfers(null, null, null, null, null, null, null, null);

    ListTransfersCommand command = receivedCommands.get(0);
    assertThat(command.iban()).isNull();
    assertThat(command.direction()).isNull();
    assertThat(command.statuses()).isEmpty();
    assertThat(command.type()).isNull();
    assertThat(command.from()).isNull();
    assertThat(command.to()).isNull();
    assertThat(command.page()).isNull();
    assertThat(command.size()).isNull();
  }

  @Test
  void mapsThePageOfThePortKeepingTheMaskedCounterpartyAndRenamingTheStatus() {
    Instant createdAt = Instant.parse("2026-10-19T08:30:00Z");
    TransferPageDto page = new TransferPageDto(List.of(
      new TransferListItemDto("t-1", "EXTERNAL", "OUTGOING", COUNTERPARTY_MASKED, new BigDecimal("59.73"), "EUR",
        "COMPLETED", createdAt),
      new TransferListItemDto("t-2", "INTERNAL", "INCOMING", COUNTERPARTY_MASKED, new BigDecimal("32.46"), "EUR",
        "REJECTED", createdAt.minusSeconds(3600))), 2, 2, 7, true);

    TransferListResponseDto response = serviceWith(portReturning(page))
      .listTransfers(IBAN, null, null, null, null, null, "2", "2");

    assertThat(response.page()).isEqualTo(2);
    assertThat(response.size()).isEqualTo(2);
    assertThat(response.totalElements()).isEqualTo(7L);
    assertThat(response.hasNext()).isTrue();
    assertThat(response.items()).hasSize(2);
    assertThat(response.items().get(0).transferId()).isEqualTo("t-1");
    assertThat(response.items().get(0).type()).isEqualTo("EXTERNAL");
    assertThat(response.items().get(0).direction()).isEqualTo("OUTGOING");
    assertThat(response.items().get(0).counterpartyIbanMasked()).isEqualTo(COUNTERPARTY_MASKED);
    assertThat(response.items().get(0).amount()).isEqualByComparingTo("59.73");
    assertThat(response.items().get(0).currency()).isEqualTo("EUR");
    assertThat(response.items().get(0).transferStatus()).isEqualTo("COMPLETED");
    assertThat(response.items().get(0).createdAt()).isEqualTo(createdAt);
    assertThat(response.items().get(1).transferId()).isEqualTo("t-2");
    assertThat(response.items().get(1).direction()).isEqualTo("INCOMING");
    assertThat(response.items().get(1).transferStatus()).isEqualTo("REJECTED");
    assertThat(response.items().get(1).createdAt()).isEqualTo(createdAt.minusSeconds(3600));
  }

  @Test
  void anEmptyPageIsMappedToAnEmptyList() {
    TransferListResponseDto response = serviceWith(portReturning(emptyPage()))
      .listTransfers(IBAN, null, null, null, null, null, null, null);

    assertThat(response.items()).isNotNull().isEmpty();
    assertThat(response.page()).isZero();
    assertThat(response.size()).isEqualTo(20);
    assertThat(response.totalElements()).isZero();
    assertThat(response.hasNext()).isFalse();
  }

  @Test
  void exceptionsOfThePortArePropagatedAsTheyAre() {
    IllegalArgumentException invalid = new IllegalArgumentException("size must be between 1 and 100");
    ListTransfersInPort port = command -> {
      throw invalid;
    };

    assertThatThrownBy(() -> serviceWith(port).listTransfers(IBAN, null, null, null, null, null, null, "500"))
      .isSameAs(invalid);
  }
}
