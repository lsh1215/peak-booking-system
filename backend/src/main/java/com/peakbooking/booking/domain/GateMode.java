package com.peakbooking.booking.domain;

public enum GateMode {
    REDIS,
    /**
     * Legacy value kept for old gate rows. New Redis outage handling must use REDIS_FAILOVER_PAUSED.
     */
    DB_FALLBACK,
    REDIS_FAILOVER_PAUSED,
    LOCAL_QUEUE
}
