package com.bank.transfer.rest.servicedto;

import com.bank.transfer.rest.dto.response.TransferListResponseDto;
import java.util.List;

public interface TransferListServiceDto {

  TransferListResponseDto listTransfers(String iban, String direction, List<String> statuses, String type,
    String from, String to, String page, String size);
}
