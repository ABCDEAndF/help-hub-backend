INSERT INTO users (phone, display_name, role, enabled)
SELECT '13800000000', '邻需通运营管理员', 'ADMIN', TRUE
WHERE NOT EXISTS (SELECT 1 FROM users WHERE phone = '13800000000');
