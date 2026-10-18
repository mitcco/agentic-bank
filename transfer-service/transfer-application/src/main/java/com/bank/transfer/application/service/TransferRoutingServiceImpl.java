package com.bank.transfer.application.service;

import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.port.in.CreateExternalTransferInPort;
import com.bank.transfer.application.port.in.CreateInternalTransferInPort;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import com.bank.transfer.domain.BankCode;
import com.bank.transfer.domain.Iban;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Entry point of the transfers: routes by the bank code of the destination IBAN. The destination of this bank goes
 * to the internal flow, any other bank code goes to the external flow (Saga).
 */
@Service
public class TransferRoutingServiceImpl implements CreateTransferInPort {

  private static final Logger LOG = LoggerFactory.getLogger(TransferRoutingServiceImpl.class);

  private final BankCode ourBankCode;
  private final CreateInternalTransferInPort internal;
  private final CreateExternalTransferInPort external;

  public TransferRoutingServiceImpl(BankCode ourBankCode, CreateInternalTransferInPort internal,
    CreateExternalTransferInPort external) {
    this.ourBankCode = Objects.requireNonNull(ourBankCode);
    this.internal = Objects.requireNonNull(internal);
    this.external = Objects.requireNonNull(external);
  }

  @Override
  public TransferResultDto executeTransfer(CreateTransferCommand command) {
    new Iban(command.sourceIban());
    Iban dest = new Iban(command.destIban());

    if (dest.bankCode().equals(ourBankCode)) {
      LOG.debug("Routing transfer to the internal flow");
      return internal.executeTransfer(command);
    }
    LOG.debug("Routing transfer to the external flow");
    return external.executeExternalTransfer(command);
  }
}
