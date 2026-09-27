-- Driving routes between two points, fetched once from the map service and reused.
CREATE TABLE road_legs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    leg_key VARCHAR(64) NOT NULL UNIQUE,
    distance_meters INT NOT NULL,
    duration_seconds INT NOT NULL,
    path MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
