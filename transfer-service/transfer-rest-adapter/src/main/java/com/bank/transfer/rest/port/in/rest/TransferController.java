package com.bank.transfer.rest.port.in.rest;

import com.bank.transfer.rest.dto.request.TransferRequestDto;
import com.bank.transfer.rest.dto.response.TransferResponseDto;
import com.bank.transfer.rest.servicedto.TransferServiceDto;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

  private final TransferServiceDto transferServiceDto;

  public TransferController(TransferServiceDto transferServiceDto) {
    this.transferServiceDto = transferServiceDto;
  }

  @PostMapping("/transfers")
  public TransferResponseDto executeTransfer(@RequestBody TransferRequestDto transferRequestDto) {
    return transferServiceDto.executeTransfer(transferRequestDto);
  }
}
