SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'payment_refunds'
     AND column_name = 'query_attempts') = 0,
  'ALTER TABLE payment_refunds ADD COLUMN query_attempts INT NOT NULL DEFAULT 0 AFTER status',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.columns
   WHERE table_schema = DATABASE()
     AND table_name = 'payment_refunds'
     AND column_name = 'next_query_at') = 0,
  'ALTER TABLE payment_refunds ADD COLUMN next_query_at TIMESTAMP(3) NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER query_attempts',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
   WHERE table_schema = DATABASE()
     AND table_name = 'payment_refunds'
     AND index_name = 'idx_refund_reconcile') = 0,
  'ALTER TABLE payment_refunds ADD INDEX idx_refund_reconcile (status, next_query_at)',
  'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
