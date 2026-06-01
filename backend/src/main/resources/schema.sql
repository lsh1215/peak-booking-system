CREATE TABLE IF NOT EXISTS product (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    price_amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    sale_open_at DATETIME(6) NOT NULL,
    check_in_at DATETIME(6) NOT NULL,
    check_out_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS sale_inventory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sale_event_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    total_count INT NOT NULL,
    reserved_count INT NOT NULL DEFAULT 0,
    payment_unknown_count INT NOT NULL DEFAULT 0,
    confirmed_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sale_inventory_event_product (sale_event_id, product_id),
    CONSTRAINT chk_sale_inventory_non_negative CHECK (
        total_count >= 0
        AND reserved_count >= 0
        AND payment_unknown_count >= 0
        AND confirmed_count >= 0
    ),
    CONSTRAINT chk_sale_inventory_capacity CHECK (
        reserved_count + payment_unknown_count + confirmed_count <= total_count
    )
);

CREATE TABLE IF NOT EXISTS admission_sequence (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sale_event_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    next_seq BIGINT NOT NULL DEFAULT 0,
    gate_mode VARCHAR(32) NOT NULL DEFAULT 'REDIS',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_admission_sequence_event_product (sale_event_id, product_id)
);

CREATE TABLE IF NOT EXISTS booking_admission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sale_event_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    gate_mode VARCHAR(32) NOT NULL,
    redis_seq BIGINT NULL,
    db_admission_seq BIGINT NULL,
    candidate_rank INT NULL,
    status VARCHAR(40) NOT NULL,
    booking_attempt_id VARCHAR(200) NULL,
    waiting_expires_at DATETIME(6) NULL,
    admitted_at DATETIME(6) NOT NULL,
    processing_started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_booking_admission_user (sale_event_id, product_id, user_id),
    UNIQUE KEY uk_booking_admission_seq (sale_event_id, product_id, db_admission_seq)
);

CREATE TABLE IF NOT EXISTS reservation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    admission_id BIGINT NOT NULL,
    booking_attempt_id VARCHAR(200) NOT NULL,
    sale_event_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    hold_expires_at DATETIME(6) NULL,
    unknown_inventory_deadline_at DATETIME(6) NULL,
    released_reason VARCHAR(80) NULL,
    confirmed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_attempt (booking_attempt_id),
    UNIQUE KEY uk_reservation_user_event (sale_event_id, product_id, user_id)
);

CREATE TABLE IF NOT EXISTS idempotency_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    booking_attempt_id VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    http_status INT NULL,
    business_code VARCHAR(80) NULL,
    response_snapshot TEXT NULL,
    reservation_id BIGINT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_attempt (booking_attempt_id)
);

CREATE TABLE IF NOT EXISTS payment_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    booking_attempt_id VARCHAR(200) NOT NULL,
    reservation_id BIGINT NULL,
    status VARCHAR(60) NOT NULL,
    method_type VARCHAR(40) NOT NULL,
    amount BIGINT NOT NULL,
    provider_order_id VARCHAR(200) NOT NULL,
    provider_payment_id VARCHAR(120) NULL,
    confirm_started_at DATETIME(6) NULL,
    first_unknown_at DATETIME(6) NULL,
    active_reconcile_until DATETIME(6) NULL,
    next_reconcile_at DATETIME(6) NULL,
    reconcile_attempt_count INT NOT NULL DEFAULT 0,
    last_reconcile_at DATETIME(6) NULL,
    lease_owner VARCHAR(120) NULL,
    lease_token VARCHAR(120) NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(120) NULL,
    manual_review_reason VARCHAR(300) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_attempt_booking_attempt (booking_attempt_id),
    UNIQUE KEY uk_payment_attempt_provider_order (provider_order_id)
);

CREATE TABLE IF NOT EXISTS mock_pg_payment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider_order_id VARCHAR(200) NOT NULL,
    provider_payment_id VARCHAR(120) NULL,
    scenario VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    confirm_count INT NOT NULL DEFAULT 0,
    cancel_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(120) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_mock_pg_provider_order (provider_order_id),
    UNIQUE KEY uk_mock_pg_provider_payment (provider_payment_id)
);

CREATE TABLE IF NOT EXISTS point_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    available_points BIGINT NOT NULL DEFAULT 0,
    held_points BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_point_account_user (user_id),
    CONSTRAINT chk_point_account_non_negative CHECK (available_points >= 0 AND held_points >= 0)
);

CREATE TABLE IF NOT EXISTS point_hold (
    id BIGINT NOT NULL AUTO_INCREMENT,
    booking_attempt_id VARCHAR(200) NOT NULL,
    point_account_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(40) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_point_hold_attempt (booking_attempt_id)
);
