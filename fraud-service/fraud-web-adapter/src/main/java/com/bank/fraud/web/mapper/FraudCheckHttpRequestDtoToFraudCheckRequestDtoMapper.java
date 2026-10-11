package com.bank.fraud.web.mapper;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.web.dto.request.FraudCheckHttpRequestDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapper {

    FraudCheckRequestDto fraudCheckHttpRequestDtoToFraudCheckRequestDto(FraudCheckHttpRequestDto request);
}
