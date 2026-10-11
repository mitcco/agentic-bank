package com.bank.transfer.web.mapper;

import com.bank.transfer.web.dto.request.TransferRequestDto;
import com.bank.transfer.application.command.CreateTransferCommand;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface TransferRequestDtoToCreateTransferCommandMapper {

  CreateTransferCommand transferRequestDtoToCreateTransferCommand(TransferRequestDto transferRequestDto);
}
