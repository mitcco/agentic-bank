package com.bank.transfer.application.service;

import com.bank.transfer.application.command.ListTransfersCommand;
import com.bank.transfer.application.dto.StoredTransferListDto;
import com.bank.transfer.application.dto.StoredTransferRowDto;
import com.bank.transfer.application.dto.TransferDirectionEnum;
import com.bank.transfer.application.dto.TransferListCriteria;
import com.bank.transfer.application.dto.TransferListItemDto;
import com.bank.transfer.application.dto.TransferPageDto;
import com.bank.transfer.application.dto.TransferTypeEnum;
import com.bank.transfer.application.exception.StoredTransferDataException;
import com.bank.transfer.application.port.in.ListTransfersInPort;
import com.bank.transfer.application.port.out.TransferListQueryOutPort;
import com.bank.transfer.domain.Iban;
import com.bank.transfer.domain.TransferStatusEnum;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ListTransfersServiceImpl implements ListTransfersInPort {

  private static final int DEFAULT_SIZE = 20;
  private static final int MAX_SIZE = 100;
  private static final long MAX_WINDOW = 1000;

  private final TransferListQueryOutPort transferListQueryOutPort;

  public ListTransfersServiceImpl(TransferListQueryOutPort transferListQueryOutPort) {
    this.transferListQueryOutPort = transferListQueryOutPort;
  }

  @Override
  public TransferPageDto listTransfers(ListTransfersCommand command) {
    // All the validation happens here, outside of any try: its messages are fixed and reach the client.
    Iban iban = ibanOf(command.iban());
    TransferDirectionEnum direction = directionOf(command.direction());
    Set<TransferStatusEnum> statuses = statusesOf(command.statuses());
    Set<TransferTypeEnum> types = typesOf(command.type());
    Instant from = instantOf(command.from());
    Instant to = instantOf(command.to());
    if (from != null && to != null && !from.isBefore(to)) {
      throw new IllegalArgumentException("from must be before to");
    }
    int page = pageOf(command.page());
    int size = sizeOf(command.size());
    if ((page + 1L) * size > MAX_WINDOW) {
      throw new IllegalArgumentException("page window too large");
    }
    int offset = page * size;

    StoredTransferListDto stored = transferListQueryOutPort.findByIban(
      new TransferListCriteria(iban, direction, statuses, types, from, to, offset, size));
    return new TransferPageDto(itemsOf(stored.rows(), iban), page, size, stored.total(),
      offset + (long) size < stored.total());
  }

  private static List<TransferListItemDto> itemsOf(List<StoredTransferRowDto> rows, Iban iban) {
    try {
      List<TransferListItemDto> items = new ArrayList<>(rows.size());
      for (StoredTransferRowDto row : rows) {
        boolean outgoing = row.sourceIban().equals(iban.value());
        String counterparty = outgoing ? row.destIban() : row.sourceIban();
        items.add(new TransferListItemDto(row.transferId(), row.type().name(),
          outgoing ? TransferDirectionEnum.OUTGOING.name() : TransferDirectionEnum.INCOMING.name(),
          new Iban(counterparty).masked(), row.amount().setScale(2), row.currency(), row.status().name(),
          row.createdAt()));
      }
      return items;
    } catch (ArithmeticException | IllegalArgumentException e) {
      // No cause and no logging: the original message may contain IBANs or amounts.
      throw new StoredTransferDataException();
    }
  }

  private static Iban ibanOf(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("iban is required");
    }
    return new Iban(value);
  }

  private static TransferDirectionEnum directionOf(String value) {
    if (value == null) {
      return TransferDirectionEnum.ALL;
    }
    for (TransferDirectionEnum direction : TransferDirectionEnum.values()) {
      if (direction.name().equals(value)) {
        return direction;
      }
    }
    throw new IllegalArgumentException("direction must be ALL, OUTGOING or INCOMING");
  }

  private static Set<TransferStatusEnum> statusesOf(List<String> values) {
    Set<TransferStatusEnum> statuses = EnumSet.noneOf(TransferStatusEnum.class);
    for (String value : values) {
      TransferStatusEnum found = null;
      for (TransferStatusEnum candidate : TransferStatusEnum.values()) {
        if (candidate.name().equals(value)) {
          found = candidate;
        }
      }
      if (found == null) {
        throw new IllegalArgumentException("status is not valid");
      }
      statuses.add(found);
    }
    return statuses;
  }

  private static Set<TransferTypeEnum> typesOf(String value) {
    if (value == null) {
      return EnumSet.allOf(TransferTypeEnum.class);
    }
    for (TransferTypeEnum type : TransferTypeEnum.values()) {
      if (type.name().equals(value)) {
        return EnumSet.of(type);
      }
    }
    throw new IllegalArgumentException("type must be INTERNAL or EXTERNAL");
  }

  private static Instant instantOf(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("from and to must be ISO-8601 instants");
    }
  }

  private static int pageOf(String value) {
    if (value == null) {
      return 0;
    }
    int page;
    try {
      page = Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("page must be zero or more");
    }
    if (page < 0) {
      throw new IllegalArgumentException("page must be zero or more");
    }
    return page;
  }

  private static int sizeOf(String value) {
    if (value == null) {
      return DEFAULT_SIZE;
    }
    int size;
    try {
      size = Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("size must be between 1 and 100");
    }
    if (size < 1 || size > MAX_SIZE) {
      throw new IllegalArgumentException("size must be between 1 and 100");
    }
    return size;
  }
}
