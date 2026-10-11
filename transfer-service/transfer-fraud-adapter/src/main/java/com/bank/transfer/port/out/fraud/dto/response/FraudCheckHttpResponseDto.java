package com.bank.transfer.port.out.fraud.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FraudCheckHttpResponseDto(String transferId, String status, List<String> reasons) {

  @Override
  public String toString() {
    return "FraudCheckHttpResponseDto[transferId=" + transferId + "]";
  }
}
