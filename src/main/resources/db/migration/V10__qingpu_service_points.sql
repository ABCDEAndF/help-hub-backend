-- Qingpu production pilot data. Every insert is guarded so recovery from a
-- partially applied migration cannot create duplicate operating records.
INSERT INTO service_points (name, address, latitude, longitude, status, opens_at, closes_at)
SELECT '邻需通·夏阳公益服务点', '上海市青浦区青松路245号附近', 31.1518500, 121.1294200,
       'ACTIVE', '08:30:00', '16:30:00'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM service_points WHERE name = '邻需通·夏阳公益服务点'
);

INSERT INTO service_points (name, address, latitude, longitude, status, opens_at, closes_at)
SELECT '邻需通·青浦工业园区公益服务点', '上海市青浦区清河湾路1150号附近', 31.1811800, 121.0926800,
       'ACTIVE', '09:00:00', '17:00:00'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM service_points WHERE name = '邻需通·青浦工业园区公益服务点'
);

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-RICE-5KG', '公益大米 5 千克', 'FOOD', '袋', 0, 80, 0, 15
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-RICE-5KG');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-WATER-12', '公益饮用水 12 瓶装', 'WATER', '箱', 0, 60, 0, 12
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-WATER-12');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-HYGIENE-01', '公益基础卫生用品包', 'HYGIENE', '包', 0, 60, 0, 12
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-HYGIENE-01');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-EMERGENCY-01', '公益应急生活包', 'EMERGENCY', '包', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-EMERGENCY-01');

INSERT INTO mobile_carts
    (code, name, capacity_units, status, latitude, longitude, last_location_at)
SELECT 'QP-CART-001', '青浦公益补给车一号', 120, 'AVAILABLE', 31.1518500, 121.1294200,
       CURRENT_TIMESTAMP(3)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM mobile_carts WHERE code = 'QP-CART-001');

INSERT INTO mobile_carts
    (code, name, capacity_units, status, latitude, longitude, last_location_at)
SELECT 'QP-CART-002', '青浦公益补给车二号', 100, 'AVAILABLE', 31.1811800, 121.0926800,
       CURRENT_TIMESTAMP(3)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM mobile_carts WHERE code = 'QP-CART-002');
