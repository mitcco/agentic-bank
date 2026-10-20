package com.bank.transfer.application.service;

import com.bank.transfer.application.dto.StoredTransferDto;
import com.bank.transfer.application.dto.TransferDetailsDto;
import com.bank.transfer.application.exception.StoredTransferDataException;
import com.bank.transfer.application.exception.TransferNotFoundException;
import com.bank.transfer.application.port.in.GetTransferInPort;
import com.bank.transfer.application.port.out.TransferQueryOutPort;
import com.bank.transfer.domain.Iban;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class GetTransferServiceImpl implements GetTransferInPort {

  private static final String REASON_SEPARATOR = "; ";

  private final TransferQueryOutPort transferQueryOutPort;

  public GetTransferServiceImpl(TransferQueryOutPort transferQueryOutPort) {
    this.transferQueryOutPort = transferQueryOutPort;
  }

  @Override
  public TransferDetailsDto getTransfer(String transferId) {
    Objects.requireNonNull(transferId, "transferId");
    if (transferId.isBlank()) {
      throw new IllegalArgumentException("transferId must not be blank");
    }
    StoredTransferDto stored = transferQueryOutPort.findById(transferId)
      .orElseThrow(TransferNotFoundException::new);
    try {
      return new TransferDetailsDto(stored.transferId(), new Iban(stored.sourceIban()).masked(),
        new Iban(stored.destIban()).masked(), stored.amount().setScale(2), stored.currency(),
        stored.status().name(), reasonsOf(stored.reason()));
    } catch (ArithmeticException | IllegalArgumentException e) {
      // No cause and no logging: the original message may contain IBANs or amounts.
      throw new StoredTransferDataException();
    }
  }

  private static List<String> reasonsOf(String reason) {
    if (reason == null || reason.isBlank()) {
      return List.of();
    }
    return Arrays.stream(reason.split(REASON_SEPARATOR)).map(String::trim).toList();
  }
}
