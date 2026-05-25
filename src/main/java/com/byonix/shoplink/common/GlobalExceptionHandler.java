package com.byonix.shoplink.common;

import com.byonix.shoplink.security.RateLimitFilter.RateLimitExceededException;
import com.byonix.shoplink.security.login.SecurityActionException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

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

    @ExceptionHandler({EntityNotFoundException.class})
    ResponseEntity<ApiResponse<Void>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Resource not found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiResponse<Void>> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiResponse.error("Too many requests"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("Request conflicts with existing data"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> notReadable(HttpMessageNotReadableException ignored) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid JSON body or wrong field types"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> generic() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Unexpected server error"));
    }
}
