package com.bank.transfer.rest.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record TransferDetailsResponseDto(String transferId, String sourceIbanMasked, String destIbanMasked,
                                         BigDecimal amount, String currency, String transferStatus,
                                         List<String> reasons) {

}
