package com.bank.transfer.port.out.fraud.impl;

import com.bank.transfer.application.dto.FraudCheckResultDto;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.application.port.out.FraudCheckOutPort;
import com.bank.transfer.domain.FraudStatusEnum;
import com.bank.transfer.port.out.fraud.dto.request.FraudCheckHttpRequestDto;
import com.bank.transfer.port.out.fraud.dto.response.FraudCheckHttpResponseDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Calls POST /fraud-check. Fail closed: every outcome that is not a valid 200 answer becomes
 * {@link FraudCheckUnavailableException}. Never logs IBANs, amounts or response bodies.
 */
public class HttpFraudCheckOutPortImpl implements FraudCheckOutPort {

  private static final Log LOG = LogFactory.getLog(HttpFraudCheckOutPortImpl.class);

  private final RestClient restClient;

  public HttpFraudCheckOutPortImpl(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public FraudCheckResultDto checkFraud(String transferId, String sourceIban, String destIban, BigDecimal amount,
    String currency) {
    FraudCheckHttpRequestDto request = FraudCheckHttpRequestDto.of(transferId, sourceIban, destIban, amount, currency);

    FraudCheckHttpResponseDto response;
    try {
      response = restClient.post()
                   .uri("/fraud-check")
                   .contentType(MediaType.APPLICATION_JSON)
                   .accept(MediaType.APPLICATION_JSON)
                   .body(request)
                   .retrieve()
                   .onStatus(status -> status.value() != 200, (req, res) -> {
                     throw new UnexpectedStatusException(res.getStatusCode().value());
                   })
                   .body(FraudCheckHttpResponseDto.class);
    } catch (UnexpectedStatusException e) {
      throw unavailable(transferId, "HTTP_STATUS " + e.status, null);
    } catch (ResourceAccessException e) {
      throw unavailable(transferId, "IO_ERROR", e.getCause() != null ? e.getCause() : e);
    } catch (RuntimeException e) {
      throw unavailable(transferId, "INVALID_BODY", e);
    }

    if (response == null) {
      throw unavailable(transferId, "INVALID_BODY", null);
    }
    if (!Objects.equals(transferId, response.transferId())) {
      throw unavailable(transferId, "TRANSFER_ID_MISMATCH", null);
    }

    FraudCheckResultDto result = toResult(transferId, response);
    LOG.info("Fraud check for transfer " + transferId + ": " + result.decision() + " " + result.reasons());
    return result;
  }

  private FraudCheckResultDto toResult(String transferId, FraudCheckHttpResponseDto response) {
    if ("ALLOWED".equals(response.status())) {
      return new FraudCheckResultDto(FraudStatusEnum.ALLOWED, List.of());
    }
    if ("REJECTED".equals(response.status())) {
      List<String> reasons = response.reasons() == null ? List.of()
        : response.reasons().stream().filter(r -> r != null && !r.isBlank()).toList();
      if (reasons.isEmpty()) {
        throw unavailable(transferId, "REJECTED_WITHOUT_REASONS", null);
      }
      return new FraudCheckResultDto(FraudStatusEnum.REJECTED, reasons);
    }
    throw unavailable(transferId, "UNKNOWN_STATUS", null);
  }

  private FraudCheckUnavailableException unavailable(String transferId, String category, Throwable cause) {
    LOG.warn("Fraud check unavailable for transfer " + transferId + ": " + category
      + (cause == null ? "" : " (" + cause.getClass().getSimpleName() + ")"));
    return new FraudCheckUnavailableException(transferId);
  }

  private static final class UnexpectedStatusException extends RuntimeException {

    private final int status;

    private UnexpectedStatusException(int status) {
      super(null, null, false, false);
      this.status = status;
    }
  }
}
