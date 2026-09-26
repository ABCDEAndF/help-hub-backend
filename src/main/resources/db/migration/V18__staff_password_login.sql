-- Staff accounts (couriers, service-point staff) sign in with their own password. Only a
-- BCrypt hash is stored; accounts are created through the admin API, never in migrations,
-- because this repository is public.
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100) NULL AFTER enabled;
