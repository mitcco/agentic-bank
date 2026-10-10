package com.bank.transfer.adapter.in.web.servicedto;

import com.bank.transfer.adapter.in.web.dto.TransferRequestDto;
import com.bank.transfer.adapter.in.web.dto.TransferResponseDto;

public interface TransferServiceDto {

  TransferResponseDto executeTransfer(TransferRequestDto transferRequestDto);
}
