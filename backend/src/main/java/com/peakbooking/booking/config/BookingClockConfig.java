package com.peakbooking.booking.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BookingClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
