ALTER TABLE notification_deliveries
  DROP INDEX uk_notification_event_recipient,
  ADD UNIQUE KEY uk_notification_event_recipient_channel
    (event_id, recipient_user_id, template_code, channel);

ALTER TABLE notification_deliveries
  ADD COLUMN attempts INT NOT NULL DEFAULT 0 AFTER status,
  ADD COLUMN next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER attempts,
  ADD INDEX idx_notification_dispatch (channel, status, next_attempt_at);
