package com.ecommerce.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCodeBase errorCode;

    public BusinessException(ErrorCodeBase errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCodeBase errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
