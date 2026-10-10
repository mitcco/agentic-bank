package com.bank.transfer.adapter.in.web.controller;

import com.bank.transfer.adapter.in.web.dto.TransferRequestDto;
import com.bank.transfer.adapter.in.web.dto.TransferResponseDto;
import com.bank.transfer.adapter.in.web.servicedto.TransferServiceDto;
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
