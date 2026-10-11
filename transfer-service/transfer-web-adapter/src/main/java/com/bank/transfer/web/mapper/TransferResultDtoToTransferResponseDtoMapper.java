package com.bank.transfer.web.mapper;

import com.bank.transfer.web.dto.response.TransferResponseDto;
import com.bank.transfer.application.dto.TransferResultDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface TransferResultDtoToTransferResponseDtoMapper {

  TransferResponseDto transferResultDtoToTransferResponseDto(TransferResultDto transferResultDto);
}
