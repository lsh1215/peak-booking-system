package com.ecommerce.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCodeBase {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON_001", "Invalid input"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_002", "Resource not found"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_003", "Internal server error"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_004", "Access denied");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
