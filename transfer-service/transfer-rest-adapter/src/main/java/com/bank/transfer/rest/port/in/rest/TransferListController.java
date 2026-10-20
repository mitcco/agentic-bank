package com.bank.transfer.rest.port.in.rest;

import com.bank.transfer.rest.dto.response.TransferListResponseDto;
import com.bank.transfer.rest.servicedto.TransferListServiceDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferListController {

  private final TransferListServiceDto transferListServiceDto;

  public TransferListController(TransferListServiceDto transferListServiceDto) {
    this.transferListServiceDto = transferListServiceDto;
  }

  /**
   * Lists the internal and external transfers of an IBAN. All the parameters are passed as they are: the service
   * validates them, so that an invalid value always gives the same 400 body. There is no access control: anyone
   * who knows an IBAN reads its history (counterparties masked, amounts in clear). Always 200 when valid.
   */
  @GetMapping("/transfers")
  public TransferListResponseDto listTransfers(
    @RequestParam(required = false) String iban,
    @RequestParam(required = false) String direction,
    @RequestParam(name = "status", required = false) List<String> statuses,
    @RequestParam(required = false) String type,
    @RequestParam(required = false) String from,
    @RequestParam(required = false) String to,
    @RequestParam(required = false) String page,
    @RequestParam(required = false) String size) {
    return transferListServiceDto.listTransfers(iban, direction, statuses, type, from, to, page, size);
  }
}
