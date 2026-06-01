INSERT INTO product (
    id, name, price_amount, currency, sale_open_at, check_in_at, check_out_at
)
VALUES (
    1,
    'Peak Room',
    10000,
    'KRW',
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6),
    DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY)
)
ON DUPLICATE KEY UPDATE id = id;

INSERT INTO sale_inventory (
    sale_event_id, product_id, total_count, reserved_count, payment_unknown_count, confirmed_count
)
VALUES (1, 1, 10, 0, 0, 0)
ON DUPLICATE KEY UPDATE id = id;
