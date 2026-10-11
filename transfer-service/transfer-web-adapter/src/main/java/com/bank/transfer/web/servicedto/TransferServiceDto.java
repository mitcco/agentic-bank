package com.bank.transfer.web.servicedto;

import com.bank.transfer.web.dto.request.TransferRequestDto;
import com.bank.transfer.web.dto.response.TransferResponseDto;

public interface TransferServiceDto {

  TransferResponseDto executeTransfer(TransferRequestDto transferRequestDto);
}
