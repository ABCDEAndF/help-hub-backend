-- Residents can pick a stocked item; such requests are approved or rejected automatically.
ALTER TABLE supply_requests
    ADD COLUMN inventory_item_id BIGINT NULL AFTER category,
    ADD COLUMN decision_note VARCHAR(255) NULL AFTER accessibility_notes,
    ADD CONSTRAINT fk_request_inventory_item FOREIGN KEY (inventory_item_id) REFERENCES inventory_items(id);

-- Actor recorded for automatic approvals, dispatches and deliveries. It has no phone,
-- no WeChat identity and is disabled, so nobody can log in as it.
INSERT INTO users (display_name, role, enabled)
SELECT '自动调度系统', 'OPERATOR', FALSE FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE display_name = '自动调度系统' AND role = 'OPERATOR' AND enabled = FALSE
);

-- One simulated cart journey: collect reserved stock at service points, deliver, return.
-- Every NOT NULL timestamp has an explicit default: on MySQL 5.7 an undeclared one would
-- silently become ON UPDATE CURRENT_TIMESTAMP (see V13).
CREATE TABLE cart_trips (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cart_id BIGINT NOT NULL,
    route_plan_id BIGINT NULL,
    status ENUM('ACTIVE', 'COMPLETED') NOT NULL DEFAULT 'ACTIVE',
    start_latitude DECIMAL(10, 7) NOT NULL,
    start_longitude DECIMAL(10, 7) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3) NULL,
    distance_meters INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_trip_cart FOREIGN KEY (cart_id) REFERENCES mobile_carts(id),
    CONSTRAINT fk_trip_route FOREIGN KEY (route_plan_id) REFERENCES route_plans(id),
    INDEX idx_trip_cart_status (cart_id, status),
    INDEX idx_trip_status (status)
);

CREATE TABLE cart_trip_stops (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL,
    stop_order INT NOT NULL,
    stop_type ENUM('PICKUP', 'DROPOFF', 'RETURN') NOT NULL,
    service_point_id BIGINT NULL,
    request_id BIGINT NULL,
    reservation_id BIGINT NULL,
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    arrive_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    depart_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    status ENUM('PENDING', 'DONE') NOT NULL DEFAULT 'PENDING',
    completed_at TIMESTAMP(3) NULL,
    CONSTRAINT fk_trip_stop_trip FOREIGN KEY (trip_id) REFERENCES cart_trips(id),
    CONSTRAINT fk_trip_stop_point FOREIGN KEY (service_point_id) REFERENCES service_points(id),
    CONSTRAINT fk_trip_stop_request FOREIGN KEY (request_id) REFERENCES supply_requests(id),
    CONSTRAINT fk_trip_stop_reservation FOREIGN KEY (reservation_id) REFERENCES reservations(id),
    UNIQUE KEY uk_trip_stop_order (trip_id, stop_order),
    INDEX idx_trip_stop_status (status, arrive_at)
);
