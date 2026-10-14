package com.bank.fraud.rest.mapper;

import com.bank.fraud.application.dto.FraudCheckRequestDto;
import com.bank.fraud.rest.dto.request.FraudCheckHttpRequestDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FraudCheckHttpRequestDtoToFraudCheckRequestDtoMapper {

    FraudCheckRequestDto fraudCheckHttpRequestDtoToFraudCheckRequestDto(FraudCheckHttpRequestDto request);
}
