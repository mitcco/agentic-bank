package com.bank.transfer.rest.mapper;

import com.bank.transfer.rest.dto.response.TransferResponseDto;
import com.bank.transfer.application.dto.TransferResultDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface TransferResultDtoToTransferResponseDtoMapper {

  TransferResponseDto transferResultDtoToTransferResponseDto(TransferResultDto transferResultDto);
}
