package com.bank.transfer.rest.servicedto.internal;

import com.bank.transfer.application.command.ListTransfersCommand;
import com.bank.transfer.application.port.in.ListTransfersInPort;
import com.bank.transfer.rest.dto.response.TransferListResponseDto;
import com.bank.transfer.rest.mapper.TransferPageDtoToTransferListResponseDtoMapper;
import com.bank.transfer.rest.servicedto.TransferListServiceDto;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TransferListServiceDtoImpl implements TransferListServiceDto {

  private final ListTransfersInPort listTransfersInPort;
  private final TransferPageDtoToTransferListResponseDtoMapper responseMapper;

  public TransferListServiceDtoImpl(ListTransfersInPort listTransfersInPort,
    TransferPageDtoToTransferListResponseDtoMapper responseMapper) {
    this.listTransfersInPort = listTransfersInPort;
    this.responseMapper = responseMapper;
  }

  @Override
  public TransferListResponseDto listTransfers(String iban, String direction, List<String> statuses, String type,
    String from, String to, String page, String size) {
    ListTransfersCommand command = new ListTransfersCommand(iban, direction, statuses, type, from, to, page, size);
    return responseMapper.transferPageDtoToTransferListResponseDto(listTransfersInPort.listTransfers(command));
  }
}
