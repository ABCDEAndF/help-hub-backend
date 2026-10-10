-- Residents who use the web version have no WeChat openid. Their browser keeps a random device
-- key and only its SHA-256 is stored here, in its own column, so WeChat pushes and payments
-- (which select on wechat_open_id) never reach a web resident.
ALTER TABLE users ADD COLUMN web_device_hash CHAR(64) NULL;
ALTER TABLE users ADD CONSTRAINT uk_users_web_device_hash UNIQUE (web_device_hash);
