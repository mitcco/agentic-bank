package com.bank.transfer.rest.port.in.rest;

import com.bank.transfer.rest.dto.response.TransferDetailsResponseDto;
import com.bank.transfer.rest.servicedto.TransferQueryServiceDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferQueryController {

  private final TransferQueryServiceDto transferQueryServiceDto;

  public TransferQueryController(TransferQueryServiceDto transferQueryServiceDto) {
    this.transferQueryServiceDto = transferQueryServiceDto;
  }

  /**
   * Reads a transfer by id. There is no access control: anyone who knows the id reads the transfer (IBANs masked,
   * amount and currency in clear). Always 200 when found, whatever the status.
   */
  @GetMapping("/transfers/{id}")
  public TransferDetailsResponseDto getTransfer(@PathVariable String id) {
    return transferQueryServiceDto.getTransfer(id);
  }
}
