package com.peakbooking.booking.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.peakbooking.booking.domain.BookingErrorCode;
import com.peakbooking.booking.infrastructure.jpa.BookingJpaRepository;
import com.peakbooking.common.exception.BusinessException;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class CheckoutApplicationServiceTest {

    @Test
    void should_reject_before_repository_access_when_checkout_read_bulkhead_is_full() {
        BookingJpaRepository repository = mock(BookingJpaRepository.class);
        CheckoutApplicationService service = new CheckoutApplicationService(
                BookingApplicationServiceTest.propertiesWithBulkhead(6, 5, 1, 0),
                repository,
                mock(AttemptTokenService.class),
                Clock.systemUTC()
        );

        assertThatThrownBy(() -> service.checkout(1, 1))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(BookingErrorCode.SERVICE_BUSY));
        verifyNoInteractions(repository);
    }
}
