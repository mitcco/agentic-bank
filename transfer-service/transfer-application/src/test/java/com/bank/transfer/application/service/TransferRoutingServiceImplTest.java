package com.bank.transfer.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.application.port.in.CreateExternalTransferInPort;
import com.bank.transfer.application.port.in.CreateInternalTransferInPort;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.domain.BankCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferRoutingServiceImplTest {

  private static final String SOURCE = "IT60X0542811101000000000001";
  private static final String OWN_DEST = "IT60X0542811101000000000002";
  private static final String BLOCKED_DEMO_DEST = "IT60X0542811101000000000099";
  private static final String OTHER_BANK_DEST = "IT60X0306909606100000012345";
  private static final BankCode OUR_BANK = new BankCode("05428");

  private final List<CreateTransferCommand> internalCalls = new ArrayList<>();
  private final List<CreateTransferCommand> externalCalls = new ArrayList<>();
  private final TransferResultDto internalResult = new TransferResultDto("internal-1", "COMPLETED", List.of());
  private final TransferResultDto externalResult = new TransferResultDto("external-1", "SENT", List.of());
  private Function<CreateTransferCommand, TransferResultDto> internalBehaviour;
  private Function<CreateTransferCommand, TransferResultDto> externalBehaviour;
  private TransferRoutingServiceImpl router;

  @BeforeEach
  void setUp() {
    internalCalls.clear();
    externalCalls.clear();
    internalBehaviour = command -> internalResult;
    externalBehaviour = command -> externalResult;
    CreateInternalTransferInPort internal = command -> {
      internalCalls.add(command);
      return internalBehaviour.apply(command);
    };
    CreateExternalTransferInPort external = command -> {
      externalCalls.add(command);
      return externalBehaviour.apply(command);
    };
    router = new TransferRoutingServiceImpl(OUR_BANK, internal, external);
  }

  private static CreateTransferCommand command(String source, String dest) {
    return new CreateTransferCommand(source, dest, new BigDecimal("10.00"), "EUR");
  }

  @Test
  void ourBankCodeGoesToTheInternalFlowOnly() {
    CreateTransferCommand command = command(SOURCE, OWN_DEST);

    TransferResultDto result = router.executeTransfer(command);

    assertThat(result).isSameAs(internalResult);
    assertThat(internalCalls).hasSize(1);
    assertThat(internalCalls.get(0)).isSameAs(command);
    assertThat(externalCalls).isEmpty();
  }

  @Test
  void anotherBankCodeGoesToTheExternalFlowOnly() {
    CreateTransferCommand command = command(SOURCE, OTHER_BANK_DEST);

    TransferResultDto result = router.executeTransfer(command);

    assertThat(result).isSameAs(externalResult);
    assertThat(externalCalls).hasSize(1);
    assertThat(externalCalls.get(0)).isSameAs(command);
    assertThat(internalCalls).isEmpty();
  }

  @Test
  void missingInternalDestinationIsTheInternalFlow404() {
    AccountNotFoundException notFound = new AccountNotFoundException("Destination Account not found");
    internalBehaviour = command -> {
      throw notFound;
    };

    assertThatThrownBy(() -> router.executeTransfer(command(SOURCE, "IT60X0542811101000000N00001")))
      .isSameAs(notFound);

    assertThat(internalCalls).hasSize(1);
    assertThat(externalCalls).isEmpty();
  }

  @Test
  void blockedDemoIbanStaysInternal() {
    router.executeTransfer(command(SOURCE, BLOCKED_DEMO_DEST));

    assertThat(internalCalls).hasSize(1);
    assertThat(externalCalls).isEmpty();
  }

  @Test
  void destinationIsNormalizedBeforeComparing() {
    CreateTransferCommand command = command(SOURCE, "it60 x054 2811 1010 0000 0000 002");

    router.executeTransfer(command);

    assertThat(internalCalls).hasSize(1);
    assertThat(internalCalls.get(0)).isSameAs(command);
    assertThat(externalCalls).isEmpty();
  }

  @Test
  void invalidDestinationIsRefusedBeforeCallingAnyFlow() {
    String shortDest = OWN_DEST.substring(1);

    assertThatThrownBy(() -> router.executeTransfer(command(SOURCE, shortDest)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Malformed IBAN, it has to be 27 characters");

    assertThat(internalCalls).isEmpty();
    assertThat(externalCalls).isEmpty();
  }

  @Test
  void invalidSourceIsReportedFirst() {
    String badSource = SOURCE.substring(0, 25) + "$1";
    String shortDest = OWN_DEST.substring(1);

    assertThatThrownBy(() -> router.executeTransfer(command(badSource, shortDest)))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("Malformed IBAN, invalid characters");

    assertThat(internalCalls).isEmpty();
    assertThat(externalCalls).isEmpty();
  }

  @Test
  void externalFlowExceptionsArePropagatedAsTheyAre() {
    FraudCheckUnavailableException unavailable = new FraudCheckUnavailableException("t-1");
    externalBehaviour = command -> {
      throw unavailable;
    };

    assertThatThrownBy(() -> router.executeTransfer(command(SOURCE, OTHER_BANK_DEST))).isSameAs(unavailable);

    assertThat(externalCalls).hasSize(1);
    assertThat(internalCalls).isEmpty();
  }

  @Test
  void nullCommandIsRefused() {
    assertThatThrownBy(() -> router.executeTransfer(null)).isInstanceOf(NullPointerException.class);

    assertThat(internalCalls).isEmpty();
    assertThat(externalCalls).isEmpty();
  }

  @Test
  void nullCollaboratorsAreRefused() {
    CreateInternalTransferInPort internal = command -> internalResult;
    CreateExternalTransferInPort external = command -> externalResult;

    assertThatThrownBy(() -> new TransferRoutingServiceImpl(null, internal, external))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TransferRoutingServiceImpl(OUR_BANK, null, external))
      .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TransferRoutingServiceImpl(OUR_BANK, internal, null))
      .isInstanceOf(NullPointerException.class);
  }

  @Test
  void internalPortDoesNotExtendTheEntryPort() {
    assertThat(CreateTransferInPort.class.isAssignableFrom(CreateInternalTransferInPort.class)).isFalse();
  }
}
