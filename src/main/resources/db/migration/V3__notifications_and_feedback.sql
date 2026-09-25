CREATE TABLE notification_deliveries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id CHAR(36) NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    template_code VARCHAR(120) NOT NULL,
    channel ENUM('WECHAT', 'IN_APP') NOT NULL DEFAULT 'IN_APP',
    status ENUM('PENDING', 'SENT', 'FAILED') NOT NULL DEFAULT 'PENDING',
    payload JSON NOT NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_notification_user FOREIGN KEY (recipient_user_id) REFERENCES users(id),
    UNIQUE KEY uk_notification_event_recipient (event_id, recipient_user_id, template_code),
    INDEX idx_notification_user_time (recipient_user_id, created_at)
);

CREATE TABLE service_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NOT NULL UNIQUE,
    resident_id BIGINT NOT NULL,
    rating TINYINT NOT NULL,
    comments VARCHAR(1000) NULL,
    previous_travel_minutes INT NULL,
    current_travel_minutes INT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_feedback_request FOREIGN KEY (request_id) REFERENCES supply_requests(id),
    CONSTRAINT fk_feedback_resident FOREIGN KEY (resident_id) REFERENCES users(id),
    CONSTRAINT chk_feedback_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_feedback_travel CHECK (
      (previous_travel_minutes IS NULL OR previous_travel_minutes >= 0) AND
      (current_travel_minutes IS NULL OR current_travel_minutes >= 0)
    )
);

