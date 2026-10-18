package com.bank.transfer.rest.port.in.rest;

import com.bank.transfer.rest.dto.request.TransferRequestDto;
import com.bank.transfer.rest.dto.response.TransferResponseDto;
import com.bank.transfer.rest.servicedto.TransferServiceDto;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

  /**
   * Statuses of a transfer accepted but not concluded yet: they answer 202, any other status answers 200.
   */
  private static final Set<String> ACCEPTED_STATUSES = Set.of("SENT", "UNKNOWN");

  private final TransferServiceDto transferServiceDto;

  public TransferController(TransferServiceDto transferServiceDto) {
    this.transferServiceDto = transferServiceDto;
  }

  @PostMapping("/transfers")
  public ResponseEntity<TransferResponseDto> executeTransfer(@RequestBody TransferRequestDto transferRequestDto) {
    TransferResponseDto body = transferServiceDto.executeTransfer(transferRequestDto);
    HttpStatus status = ACCEPTED_STATUSES.contains(body.transferStatus()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
    return ResponseEntity.status(status).body(body);
  }
}
