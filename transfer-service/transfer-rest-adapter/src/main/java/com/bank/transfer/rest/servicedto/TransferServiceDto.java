package com.bank.transfer.rest.servicedto;

import com.bank.transfer.rest.dto.request.TransferRequestDto;
import com.bank.transfer.rest.dto.response.TransferResponseDto;

public interface TransferServiceDto {

  TransferResponseDto executeTransfer(TransferRequestDto transferRequestDto);
}
