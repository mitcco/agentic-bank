package com.bank.transfer.adapter.in.web.mapper;

import com.bank.transfer.adapter.in.web.dto.TransferRequestDto;
import com.bank.transfer.application.command.CreateTransferCommand;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface TransferRequestDtoToCreateTransferCommandMapper {

  CreateTransferCommand transferRequestDtoToCreateTransferCommand(TransferRequestDto transferRequestDto);
}
