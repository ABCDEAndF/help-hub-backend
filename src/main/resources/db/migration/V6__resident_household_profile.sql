ALTER TABLE users
  ADD COLUMN household_size TINYINT NOT NULL DEFAULT 1 AFTER locale,
  ADD CONSTRAINT chk_household_size CHECK (household_size BETWEEN 1 AND 20);
