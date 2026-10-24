package com.bank.transfer.rest.port.in.rest.exception;

import com.bank.transfer.application.exception.AccountNotFoundException;
import com.bank.transfer.application.exception.FraudCheckUnavailableException;
import com.bank.transfer.application.exception.StoredTransferDataException;
import com.bank.transfer.application.exception.TransferNotFoundException;
import com.bank.transfer.domain.exception.DifferentCurrencyException;
import com.bank.transfer.domain.exception.InsufficientFundException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WebExceptionHandler {

  private static final String FRAUD_UNAVAILABLE_DETAIL =
    "Fraud check is temporarily unavailable: the transfer was not executed";

  private static final URI TRANSFERS_INSTANCE = URI.create("/transfers");

  private static final URI ACCOUNTS_INSTANCE = URI.create("/accounts");

  /**
   * The instance is fixed: Spring would otherwise fill it with the requested path, which contains the IBAN.
   */
  @ExceptionHandler(AccountNotFoundException.class)
  public ProblemDetail handleAccountNotFound(AccountNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    problem.setInstance(ACCOUNTS_INSTANCE);
    return problem;
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

  /**
   * The instance is fixed: Spring would otherwise fill it with the requested path, which contains the id.
   */
  @ExceptionHandler(TransferNotFoundException.class)
  public ProblemDetail handleTransferNotFound(TransferNotFoundException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    problem.setInstance(TRANSFERS_INSTANCE);
    return problem;
  }

  /**
   * The detail is the fixed message of the exception; the instance is fixed like the 404 one.
   */
  @ExceptionHandler(StoredTransferDataException.class)
  public ProblemDetail handleStoredTransferData(StoredTransferDataException e) {
    ProblemDetail problem =
      ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    problem.setInstance(TRANSFERS_INSTANCE);
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
  }
}
