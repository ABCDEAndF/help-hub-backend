ALTER TABLE payment_refunds
  ADD COLUMN query_attempts INT NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN next_query_at TIMESTAMP(3) NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER query_attempts,
  ADD INDEX idx_refund_reconcile (status, next_query_at);
