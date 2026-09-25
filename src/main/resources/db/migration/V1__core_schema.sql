CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    wechat_open_id VARCHAR(128) NULL UNIQUE,
    phone VARCHAR(32) NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    role ENUM('RESIDENT', 'OPERATOR', 'ADMIN') NOT NULL DEFAULT 'RESIDENT',
    locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);

CREATE TABLE service_points (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(255) NOT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    status ENUM('PLANNED', 'ACTIVE', 'PAUSED', 'CLOSED') NOT NULL DEFAULT 'PLANNED',
    opens_at TIME NULL,
    closes_at TIME NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_service_points_status (status),
    INDEX idx_service_points_coordinates (latitude, longitude)
);

CREATE TABLE mobile_carts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    capacity_units INT NOT NULL,
    status ENUM('AVAILABLE', 'LOADING', 'IN_SERVICE', 'MAINTENANCE', 'OFFLINE') NOT NULL DEFAULT 'AVAILABLE',
    latitude DECIMAL(10, 7) NULL,
    longitude DECIMAL(10, 7) NULL,
    last_location_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_cart_capacity CHECK (capacity_units > 0)
);

CREATE TABLE inventory_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    service_point_id BIGINT NOT NULL,
    sku VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    category VARCHAR(80) NOT NULL,
    unit VARCHAR(40) NOT NULL,
    available_quantity INT NOT NULL DEFAULT 0,
    reserved_quantity INT NOT NULL DEFAULT 0,
    reorder_threshold INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_inventory_service_point FOREIGN KEY (service_point_id) REFERENCES service_points(id),
    CONSTRAINT chk_inventory_nonnegative CHECK (
      available_quantity >= 0 AND reserved_quantity >= 0 AND reserved_quantity <= available_quantity
    ),
    INDEX idx_inventory_service_point (service_point_id, category)
);

CREATE TABLE supply_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    resident_id BIGINT NOT NULL,
    category VARCHAR(80) NOT NULL,
    item_description VARCHAR(500) NOT NULL,
    quantity INT NOT NULL,
    urgency ENUM('LOW', 'NORMAL', 'HIGH', 'CRITICAL') NOT NULL DEFAULT 'NORMAL',
    status ENUM('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'SCHEDULED', 'FULFILLED', 'REJECTED', 'CANCELLED') NOT NULL DEFAULT 'SUBMITTED',
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    approximate_address VARCHAR(255) NULL,
    accessibility_notes VARCHAR(500) NULL,
    preferred_start TIMESTAMP(3) NULL,
    preferred_end TIMESTAMP(3) NULL,
    assigned_service_point_id BIGINT NULL,
    assigned_cart_id BIGINT NULL,
    reviewed_by BIGINT NULL,
    reviewed_at TIMESTAMP(3) NULL,
    fulfilled_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_request_resident FOREIGN KEY (resident_id) REFERENCES users(id),
    CONSTRAINT fk_request_point FOREIGN KEY (assigned_service_point_id) REFERENCES service_points(id),
    CONSTRAINT fk_request_cart FOREIGN KEY (assigned_cart_id) REFERENCES mobile_carts(id),
    CONSTRAINT fk_request_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(id),
    CONSTRAINT chk_request_quantity CHECK (quantity > 0),
    INDEX idx_requests_resident_created (resident_id, created_at),
    INDEX idx_requests_status_urgency (status, urgency, created_at),
    INDEX idx_requests_geo (latitude, longitude)
);

CREATE TABLE reservations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id BIGINT NOT NULL,
    resident_id BIGINT NOT NULL,
    inventory_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    status ENUM('HELD', 'CONFIRMED', 'COLLECTED', 'EXPIRED', 'CANCELLED') NOT NULL DEFAULT 'HELD',
    pickup_code_hash VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_reservation_request FOREIGN KEY (request_id) REFERENCES supply_requests(id),
    CONSTRAINT fk_reservation_resident FOREIGN KEY (resident_id) REFERENCES users(id),
    CONSTRAINT fk_reservation_inventory FOREIGN KEY (inventory_item_id) REFERENCES inventory_items(id),
    CONSTRAINT chk_reservation_quantity CHECK (quantity > 0),
    INDEX idx_reservation_expiry (status, expires_at)
);

CREATE TABLE route_plans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    service_date DATE NOT NULL,
    status ENUM('PENDING', 'COMPUTING', 'READY', 'DISPATCHED', 'COMPLETED', 'FAILED') NOT NULL DEFAULT 'PENDING',
    objective_distance_meters INT NULL,
    baseline_distance_meters INT NULL,
    requested_by BIGINT NOT NULL,
    error_message VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_route_requester FOREIGN KEY (requested_by) REFERENCES users(id),
    INDEX idx_route_date_status (service_date, status)
);

CREATE TABLE route_stops (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    route_plan_id BIGINT NOT NULL,
    cart_id BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    stop_order INT NOT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    demand_units INT NOT NULL,
    estimated_arrival TIMESTAMP(3) NULL,
    CONSTRAINT fk_stop_route FOREIGN KEY (route_plan_id) REFERENCES route_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_stop_cart FOREIGN KEY (cart_id) REFERENCES mobile_carts(id),
    CONSTRAINT fk_stop_request FOREIGN KEY (request_id) REFERENCES supply_requests(id),
    UNIQUE KEY uk_route_cart_stop (route_plan_id, cart_id, stop_order)
);

CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_status INT NULL,
    response_body JSON NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_idempotency_expiry (expires_at)
);

CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_user_id BIGINT NULL,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id VARCHAR(80) NOT NULL,
    before_data JSON NULL,
    after_data JSON NULL,
    correlation_id VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_audit_entity (entity_type, entity_id, occurred_at),
    INDEX idx_audit_actor (actor_user_id, occurred_at)
);

CREATE TABLE outbox_events (
    id CHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(80) NOT NULL,
    aggregate_id VARCHAR(80) NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    routing_key VARCHAR(120) NOT NULL,
    payload JSON NOT NULL,
    status ENUM('PENDING', 'PUBLISHED', 'FAILED') NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at TIMESTAMP(3) NULL,
    last_error VARCHAR(1000) NULL,
    INDEX idx_outbox_publish (status, next_attempt_at, created_at)
);

CREATE TABLE processed_messages (
    event_id CHAR(36) NOT NULL,
    consumer_name VARCHAR(120) NOT NULL,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id, consumer_name)
);

CREATE TABLE impact_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_type ENUM('REQUEST_FULFILLED', 'HOUSEHOLD_SERVED', 'CART_VISIT_COMPLETED', 'SATISFACTION_RECORDED') NOT NULL,
    resident_id BIGINT NULL,
    request_id BIGINT NULL,
    route_plan_id BIGINT NULL,
    value_number DECIMAL(14, 3) NULL,
    metadata JSON NULL,
    is_test BOOLEAN NOT NULL DEFAULT FALSE,
    occurred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_impact_resident FOREIGN KEY (resident_id) REFERENCES users(id),
    CONSTRAINT fk_impact_request FOREIGN KEY (request_id) REFERENCES supply_requests(id),
    CONSTRAINT fk_impact_route FOREIGN KEY (route_plan_id) REFERENCES route_plans(id),
    INDEX idx_impact_type_time (event_type, occurred_at),
    INDEX idx_impact_test (is_test)
);
