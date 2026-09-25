INSERT IGNORE INTO users (id, phone, display_name, role) VALUES
  (1, '13800000000', 'Local Administrator', 'ADMIN'),
  (2, '13800000001', 'Local Operator', 'OPERATOR'),
  (3, '13800000003', 'Local Resident', 'RESIDENT');

INSERT IGNORE INTO service_points (id, name, address, latitude, longitude, status, opens_at, closes_at) VALUES
  (1, '浦东社区补给站', '上海市浦东新区', 31.2303900, 121.4737000, 'ACTIVE', '09:00:00', '18:00:00'),
  (2, '闵行移动补给停靠点', '上海市闵行区', 31.1128200, 121.3815600, 'ACTIVE', '10:00:00', '17:00:00');

INSERT IGNORE INTO mobile_carts (id, code, name, capacity_units, status, latitude, longitude, last_location_at) VALUES
  (1, 'CART-001', '邻里补给车一号', 120, 'AVAILABLE', 31.2303900, 121.4737000, CURRENT_TIMESTAMP(3)),
  (2, 'CART-002', '邻里补给车二号', 80, 'AVAILABLE', 31.1128200, 121.3815600, CURRENT_TIMESTAMP(3));

INSERT IGNORE INTO inventory_items
  (id, service_point_id, sku, name, category, unit, available_quantity, reserved_quantity, reorder_threshold) VALUES
  (1, 1, 'RICE-5KG', '大米 5 千克', 'FOOD', '袋', 100, 0, 20),
  (2, 1, 'WATER-12', '饮用水 12 瓶装', 'WATER', '箱', 80, 0, 15),
  (3, 2, 'HYGIENE-01', '基础卫生用品包', 'HYGIENE', '包', 60, 0, 10),
  (4, 2, 'MEDICAL-01', '基础急救包', 'MEDICAL', '包', 30, 0, 8);

-- One paid item exercises the payment UI when merchant credentials are configured; other supplies remain subsidized.
UPDATE inventory_items SET unit_price_fen=100 WHERE id=4;
