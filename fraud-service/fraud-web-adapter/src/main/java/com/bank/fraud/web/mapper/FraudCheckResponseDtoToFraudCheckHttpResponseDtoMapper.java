package com.bank.fraud.web.mapper;

import com.bank.fraud.application.dto.FraudCheckResponseDto;
import com.bank.fraud.web.dto.response.FraudCheckHttpResponseDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FraudCheckResponseDtoToFraudCheckHttpResponseDtoMapper {

    FraudCheckHttpResponseDto fraudCheckResponseDtoToFraudCheckHttpResponseDto(FraudCheckResponseDto response);
}
