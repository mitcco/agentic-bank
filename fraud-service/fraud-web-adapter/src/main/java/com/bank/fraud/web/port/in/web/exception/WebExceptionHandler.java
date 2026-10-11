package com.bank.fraud.web.port.in.web.exception;

import com.bank.fraud.application.exception.InvalidFraudCheckRequestException;
import com.bank.fraud.domain.exception.UnsupportedCurrencyException;
import com.bank.fraud.web.dto.response.ErrorResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.List;

@RestControllerAdvice
public class WebExceptionHandler {

    private static final String INVALID_REQUEST = "INVALID_REQUEST";
    private static final String INVALID_MESSAGE = "Request validation failed";

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleNotReadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().get(mismatch.getPath().size() - 1).getPropertyName();
            if (field != null) {
                String expected = "amount".equals(field) ? "must be a number" : "must be a string";
                return build(HttpStatus.BAD_REQUEST, INVALID_REQUEST, INVALID_MESSAGE,
                        List.of(field + ": " + expected));
            }
        }
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is not valid JSON", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleNotValid(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        return build(HttpStatus.BAD_REQUEST, INVALID_REQUEST, INVALID_MESSAGE, details);
    }

    @ExceptionHandler(InvalidFraudCheckRequestException.class)
    public ResponseEntity<ErrorResponseDto> handleInvalidCheckRequest(InvalidFraudCheckRequestException e) {
        return build(HttpStatus.BAD_REQUEST, INVALID_REQUEST, INVALID_MESSAGE, null);
    }

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ResponseEntity<ErrorResponseDto> handleUnsupportedCurrency(UnsupportedCurrencyException e) {
        return build(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY", "Only EUR is supported", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponseDto> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json", null);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponseDto> handleUnexpected(RuntimeException e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error", null);
    }

    private static ResponseEntity<ErrorResponseDto> build(HttpStatus status, String code, String message,
            List<String> details) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponseDto(code, message, details));
    }
}
