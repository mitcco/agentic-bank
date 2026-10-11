package com.bank.transfer.web.servicedto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.web.dto.request.TransferRequestDto;
import com.bank.transfer.web.dto.response.TransferResponseDto;
import com.bank.transfer.web.mapper.TransferRequestDtoToCreateTransferCommandMapperImpl;
import com.bank.transfer.web.mapper.TransferResultDtoToTransferResponseDtoMapperImpl;
import com.bank.transfer.web.servicedto.TransferServiceDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransferServiceDtoImplTest {

  private static final String IBAN_1 = "IT60X0542811101000000000001";
  private static final String IBAN_2 = "IT60X0542811101000000000002";

  private final List<CreateTransferCommand> receivedCommands = new ArrayList<>();

  private TransferServiceDto serviceWith(CreateTransferInPort port) {
    return new TransferServiceDtoImpl(port, new TransferRequestDtoToCreateTransferCommandMapperImpl(),
        new TransferResultDtoToTransferResponseDtoMapperImpl());
  }

  private static TransferRequestDto request() {
    return new TransferRequestDto(IBAN_1, IBAN_2, new BigDecimal("10.00"), "EUR");
  }

  @Test
  void mapsTheRejectionReasonsOfThePortToTheResponseKeepingTheOrder() {
    CreateTransferInPort port = command -> {
      receivedCommands.add(command);
      return new TransferResultDto("t-1", "REJECTED", List.of("a", "b"));
    };

    TransferResponseDto response = serviceWith(port).executeTransfer(request());

    assertThat(receivedCommands).hasSize(1);
    assertThat(receivedCommands.get(0).sourceIban()).isEqualTo(IBAN_1);
    assertThat(receivedCommands.get(0).destIban()).isEqualTo(IBAN_2);
    assertThat(response.transferId()).isEqualTo("t-1");
    assertThat(response.transferStatus()).isEqualTo("REJECTED");
    assertThat(response.reasons()).containsExactly("a", "b");
  }

  @Test
  void aCompletedTransferHasNoReasons() {
    CreateTransferInPort port = command -> new TransferResultDto("t-2", "COMPLETED", List.of());

    TransferResponseDto response = serviceWith(port).executeTransfer(request());

    assertThat(response.reasons()).isNotNull().isEmpty();
  }

  @Test
  void fraudCheckUnavailableIsPropagatedAsItIs() {
    FraudCheckUnavailableException unavailable = new FraudCheckUnavailableException("t-3");
    CreateTransferInPort port = command -> {
      throw unavailable;
    };

    assertThatThrownBy(() -> serviceWith(port).executeTransfer(request())).isSameAs(unavailable);
  }
}
