package com.bank.transfer.rest.servicedto.internal;

import com.bank.transfer.application.port.in.GetTransferInPort;
import com.bank.transfer.rest.dto.response.TransferDetailsResponseDto;
import com.bank.transfer.rest.mapper.TransferDetailsDtoToTransferDetailsResponseDtoMapper;
import com.bank.transfer.rest.servicedto.TransferQueryServiceDto;
import org.springframework.stereotype.Service;

@Service
public class TransferQueryServiceDtoImpl implements TransferQueryServiceDto {

  private final GetTransferInPort getTransferInPort;
  private final TransferDetailsDtoToTransferDetailsResponseDtoMapper responseMapper;

  public TransferQueryServiceDtoImpl(GetTransferInPort getTransferInPort,
    TransferDetailsDtoToTransferDetailsResponseDtoMapper responseMapper) {
    this.getTransferInPort = getTransferInPort;
    this.responseMapper = responseMapper;
  }

  @Override
  public TransferDetailsResponseDto getTransfer(String transferId) {
    return responseMapper.transferDetailsDtoToTransferDetailsResponseDto(getTransferInPort.getTransfer(transferId));
  }
}
