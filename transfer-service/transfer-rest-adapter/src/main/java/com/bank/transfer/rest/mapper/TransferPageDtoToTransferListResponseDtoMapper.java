package com.bank.transfer.rest.mapper;

import com.bank.transfer.application.dto.TransferListItemDto;
import com.bank.transfer.application.dto.TransferPageDto;
import com.bank.transfer.rest.dto.response.TransferListItemResponseDto;
import com.bank.transfer.rest.dto.response.TransferListResponseDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface TransferPageDtoToTransferListResponseDtoMapper {

  TransferListResponseDto transferPageDtoToTransferListResponseDto(TransferPageDto transferPageDto);

  @Mapping(source = "status", target = "transferStatus")
  TransferListItemResponseDto transferListItemDtoToTransferListItemResponseDto(TransferListItemDto item);
}
