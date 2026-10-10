package com.bank.transfer.adapter.in.web.mapper;

import com.bank.transfer.adapter.in.web.dto.TransferResponseDto;
import com.bank.transfer.application.command.CreateTransferCommand;
import com.bank.transfer.application.dto.TransferResultDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface TransferResultDtoToTransferResponseDtoMapper {

  TransferResponseDto transferResultDtoToTransferResponseDto(TransferResultDto transferResultDto);
}
