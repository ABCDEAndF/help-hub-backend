-- MySQL 5.7 with explicit_defaults_for_timestamp=OFF silently adds
-- "ON UPDATE CURRENT_TIMESTAMP" to the first NOT NULL TIMESTAMP column of a table.
-- For these expiry columns that meant any UPDATE reset the expiry to "now", e.g. storing an
-- idempotent response immediately expired the record and replays created duplicates.
-- An explicit DEFAULT (and no ON UPDATE clause) keeps the expiry fixed on every MySQL version.
ALTER TABLE idempotency_records
    MODIFY expires_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

ALTER TABLE reservations
    MODIFY expires_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

ALTER TABLE payment_orders
    MODIFY expires_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
