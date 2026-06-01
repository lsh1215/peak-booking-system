package com.peakbooking.booking.application;

import com.peakbooking.booking.config.BookingProperties;
import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.common.exception.BusinessException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class BookingDbWriteBulkhead {

    private final Semaphore permits;

    public BookingDbWriteBulkhead(BookingProperties properties) {
        this.permits = new Semaphore(properties.bulkhead().bookingWriteConcurrency());
    }

    public <T> T execute(Supplier<T> action) {
        if (!permits.tryAcquire()) {
            throw new BusinessException(BookingErrorCode.SERVICE_BUSY, "Booking DB write path is busy");
        }
        try {
            return action.get();
        } finally {
            permits.release();
        }
    }
}
