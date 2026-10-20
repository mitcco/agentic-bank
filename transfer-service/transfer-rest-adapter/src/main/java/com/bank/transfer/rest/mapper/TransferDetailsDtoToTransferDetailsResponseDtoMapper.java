package com.bank.transfer.rest.mapper;

import com.bank.transfer.application.dto.TransferDetailsDto;
import com.bank.transfer.rest.dto.response.TransferDetailsResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TransferDetailsDtoToTransferDetailsResponseDtoMapper {

  @Mapping(source = "status", target = "transferStatus")
  TransferDetailsResponseDto transferDetailsDtoToTransferDetailsResponseDto(TransferDetailsDto transferDetailsDto);
}
