-- Each resident's last request number, kept on their own row. Taking the next number locks only
-- that row; "INSERT ... SELECT MAX(resident_seq)" gap-locked the index, so different residents
-- submitting at the same moment could deadlock.
ALTER TABLE users ADD COLUMN request_seq INT NOT NULL DEFAULT 0;

UPDATE users u
JOIN (SELECT resident_id, MAX(resident_seq) AS last_seq FROM supply_requests GROUP BY resident_id) s
  ON s.resident_id = u.id
SET u.request_seq = s.last_seq;

-- Automatic decisions lock every stock row of the chosen item (one per service point). With this
-- index they lock only rows of that item instead of scanning, and locking, the whole table.
ALTER TABLE inventory_items ADD INDEX idx_inventory_name (name(100));
