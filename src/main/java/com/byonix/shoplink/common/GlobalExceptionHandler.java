package com.byonix.shoplink.common;

import com.byonix.shoplink.security.RateLimitFilter.RateLimitExceededException;
import com.byonix.shoplink.security.login.SecurityActionException;
import com.byonix.shoplink.service.OrderUnavailableException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(ApiResponse.validation(errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> constraint(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> errors.put(v.getPropertyPath().toString(), v.getMessage()));
        return ResponseEntity.badRequest().body(ApiResponse.validation(errors));
    }

    @ExceptionHandler({BadCredentialsException.class})
    ResponseEntity<ApiResponse<Void>> badCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Invalid credentials"));
    }

    @ExceptionHandler({AccessDeniedException.class})
    ResponseEntity<ApiResponse<Void>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Access denied"));
    }

    @ExceptionHandler(SecurityActionException.class)
    ResponseEntity<ApiResponse<Void>> securityAction(SecurityActionException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler({EntityNotFoundException.class, org.springframework.web.servlet.resource.NoResourceFoundException.class})
    ResponseEntity<ApiResponse<Void>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Resource not found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(OrderUnavailableException.class)
    ResponseEntity<ApiResponse<Void>> orderUnavailable(OrderUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage(), ex.code()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiResponse<Void>> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiResponse.error("Too many requests"));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiResponse<Void>> conflictWithMessage(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.error("Images can be up to 5 MB"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("Request conflicts with existing data"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> notReadable(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid JSON body or wrong field types"));
    }

    // A malformed or missing query parameter (e.g. a report's from/to date) is the caller's
    // mistake, not a server error.
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<ApiResponse<Void>> badParameter(Exception ex) {
        String name = ex instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException m
                ? m.getName()
                : ((org.springframework.web.bind.MissingServletRequestParameterException) ex).getParameterName();
        return ResponseEntity.badRequest().body(ApiResponse.error("Missing or invalid parameter: " + name));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> generic(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Unexpected server error"));
    }
}
