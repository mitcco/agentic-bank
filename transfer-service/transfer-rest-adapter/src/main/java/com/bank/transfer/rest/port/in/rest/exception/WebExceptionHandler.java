package com.bank.transfer.rest.port.in.rest.exception;

import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.domain.exception.DifferentCurrencyException;
import com.bank.transfer.domain.exception.InsufficientFundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WebExceptionHandler {

  private static final String FRAUD_UNAVAILABLE_DETAIL =
    "Fraud check is temporarily unavailable: the transfer was not executed";

  @ExceptionHandler(AccountNotFoundException.class)
  public ProblemDetail handleAccountNotFound(AccountNotFoundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(InsufficientFundException.class)
  public ProblemDetail handleInsufficientFund(InsufficientFundException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
  }

  @ExceptionHandler(DifferentCurrencyException.class)
  public ProblemDetail handleDifferentCurrency(DifferentCurrencyException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
  }

  @ExceptionHandler(FraudCheckUnavailableException.class)
  public ProblemDetail handleFraudCheckUnavailable(FraudCheckUnavailableException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, FRAUD_UNAVAILABLE_DETAIL);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
}
