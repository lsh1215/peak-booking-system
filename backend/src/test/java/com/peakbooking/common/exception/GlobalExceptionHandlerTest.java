package com.peakbooking.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;

class GlobalExceptionHandlerTest {

    @Test
    void should_translate_database_connection_exhaustion_to_controlled_overload_response() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        var response = handler.handleDatabaseOverload(
                new CannotCreateTransactionException("Hikari connection timeout")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Service is temporarily busy");
    }
}
