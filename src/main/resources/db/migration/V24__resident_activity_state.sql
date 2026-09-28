-- Progress of the daily resident activity run: how far today's activities have been played,
-- and which instance holds the run (CloudRun may start several replicas).
CREATE TABLE resident_activity_state (
    id TINYINT PRIMARY KEY,
    processed_until TIMESTAMP(3) NULL DEFAULT NULL,
    lease_owner VARCHAR(64) NULL,
    lease_until TIMESTAMP(3) NULL DEFAULT NULL
);

INSERT INTO resident_activity_state (id) SELECT 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM resident_activity_state WHERE id = 1);
