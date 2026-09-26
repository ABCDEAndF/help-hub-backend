-- Residents see their own request numbers (1, 2, 3 ...); the global id stays internal.
ALTER TABLE supply_requests ADD COLUMN resident_seq INT NULL AFTER resident_id;

-- Number existing requests per resident in creation order (MySQL 5.7 has no window functions).
UPDATE supply_requests r
JOIN (
    SELECT a.id, COUNT(*) AS seq
    FROM supply_requests a
    JOIN supply_requests b ON b.resident_id = a.resident_id AND b.id <= a.id
    GROUP BY a.id
) numbered ON numbered.id = r.id
SET r.resident_seq = numbered.seq;

ALTER TABLE supply_requests
    MODIFY resident_seq INT NOT NULL,
    ADD UNIQUE KEY uk_request_resident_seq (resident_id, resident_seq);
