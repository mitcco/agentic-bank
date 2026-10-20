package com.bank.transfer.rest.servicedto;

import com.bank.transfer.rest.dto.response.TransferDetailsResponseDto;

public interface TransferQueryServiceDto {

  TransferDetailsResponseDto getTransfer(String transferId);
}
