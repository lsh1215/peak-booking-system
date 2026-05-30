package com.peakbooking.common.exception;

public class EntityNotFoundException extends BusinessException {

    public EntityNotFoundException(ErrorCodeBase errorCode) {
        super(errorCode);
    }

    public EntityNotFoundException(String entityName, Object id) {
        super(CommonErrorCode.NOT_FOUND,
                String.format("%s not found with id: %s", entityName, id));
    }
}
