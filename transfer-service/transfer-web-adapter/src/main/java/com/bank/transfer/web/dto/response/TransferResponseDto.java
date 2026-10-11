package com.bank.transfer.web.dto.response;

import java.util.List;

public record TransferResponseDto(String transferId, String transferStatus, List<String> reasons) {

}
