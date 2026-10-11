package com.bank.transfer.web.servicedto.internal;

import com.bank.transfer.web.dto.request.TransferRequestDto;
import com.bank.transfer.web.dto.response.TransferResponseDto;
import com.bank.transfer.web.mapper.TransferRequestDtoToCreateTransferCommandMapper;
import com.bank.transfer.web.mapper.TransferResultDtoToTransferResponseDtoMapper;
import com.bank.transfer.web.servicedto.TransferServiceDto;
import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.TransferResultDto;
import com.bank.transfer.application.port.in.CreateTransferInPort;
import org.springframework.stereotype.Service;

@Service
public class TransferServiceDtoImpl implements TransferServiceDto {

  private final CreateTransferInPort createTransferInPort;
  private final TransferRequestDtoToCreateTransferCommandMapper commandMapper;
  private final TransferResultDtoToTransferResponseDtoMapper responseMapper;

  public TransferServiceDtoImpl(CreateTransferInPort createTransferInPort, TransferRequestDtoToCreateTransferCommandMapper commandMapper,
    TransferResultDtoToTransferResponseDtoMapper responseMapper) {
    this.createTransferInPort = createTransferInPort;
    this.commandMapper = commandMapper;
    this.responseMapper = responseMapper;
  }

  @Override
  public TransferResponseDto executeTransfer(TransferRequestDto transferRequestDto) {
    CreateTransferCommand transferCommand = commandMapper.transferRequestDtoToCreateTransferCommand(transferRequestDto);
    TransferResultDto transferResultDto = createTransferInPort.executeTransfer(transferCommand);
    return responseMapper.transferResultDtoToTransferResponseDto(transferResultDto);
  }
}
