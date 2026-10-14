package com.bank.fraud.rest.mapper;

import com.bank.fraud.application.dto.FraudCheckResponseDto;
import com.bank.fraud.rest.dto.response.FraudCheckHttpResponseDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapper {

    FraudCheckHttpResponseDto fraudCheckResponseDtoToFraudCheckHttpResponseDto(FraudCheckResponseDto response);
}
