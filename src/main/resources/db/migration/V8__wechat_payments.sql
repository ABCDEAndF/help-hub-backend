ALTER TABLE inventory_items
  ADD COLUMN unit_price_fen INT NOT NULL DEFAULT 0 AFTER unit,
  ADD CONSTRAINT chk_inventory_price CHECK (unit_price_fen >= 0);

CREATE TABLE payment_orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    out_trade_no VARCHAR(32) NOT NULL UNIQUE,
    reservation_id BIGINT NOT NULL UNIQUE,
    resident_id BIGINT NOT NULL,
    amount_fen INT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    status ENUM('CREATED', 'PREPAY', 'SUCCESS', 'CLOSED', 'REFUNDING', 'REFUNDED', 'FAILED') NOT NULL DEFAULT 'CREATED',
    wechat_prepay_id VARCHAR(128) NULL,
    wechat_transaction_id VARCHAR(64) NULL UNIQUE,
    expires_at TIMESTAMP(3) NOT NULL,
    paid_at TIMESTAMP(3) NULL,
    closed_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_payment_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    CONSTRAINT fk_payment_resident FOREIGN KEY (resident_id) REFERENCES users(id),
    CONSTRAINT chk_payment_amount CHECK (amount_fen > 0),
    INDEX idx_payment_resident_created (resident_id, created_at),
    INDEX idx_payment_status_expiry (status, expires_at)
);

CREATE TABLE payment_refunds (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    out_refund_no VARCHAR(32) NOT NULL UNIQUE,
    payment_order_id BIGINT NOT NULL,
    amount_fen INT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    status ENUM('PROCESSING', 'SUCCESS', 'CLOSED', 'ABNORMAL', 'FAILED') NOT NULL DEFAULT 'PROCESSING',
    wechat_refund_id VARCHAR(64) NULL UNIQUE,
    success_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_order_id) REFERENCES payment_orders(id),
    CONSTRAINT chk_refund_amount CHECK (amount_fen > 0),
    INDEX idx_refund_payment_status (payment_order_id, status)
);
