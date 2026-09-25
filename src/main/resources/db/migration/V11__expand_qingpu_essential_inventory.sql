-- Keep every item aligned with the resident request categories exposed by the
-- mini program: FOOD, WATER, HYGIENE, MEDICAL and OTHER.
UPDATE inventory_items
SET category = 'OTHER'
WHERE sku = 'QP-GY-EMERGENCY-01' AND category <> 'OTHER';

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-NOODLES-1KG', '公益挂面 1 千克', 'FOOD', '包', 0, 70, 0, 14
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-NOODLES-1KG');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-OIL-1L', '公益食用油 1 升', 'FOOD', '瓶', 0, 50, 0, 10
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-OIL-1L');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-TISSUE-10', '抽纸 10 包公益装', 'HYGIENE', '提', 0, 50, 0, 10
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-TISSUE-10');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-MEDICAL-01', '基础急救用品包', 'MEDICAL', '包', 0, 30, 0, 6
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-MEDICAL-01');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-LIGHT-01', '应急手电筒与电池包', 'OTHER', '套', 0, 25, 0, 5
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-LIGHT-01');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-RICE-5KG', '公益大米 5 千克', 'FOOD', '袋', 0, 100, 0, 20
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-RICE-5KG');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-WATER-12', '公益饮用水 12 瓶装', 'WATER', '箱', 0, 90, 0, 18
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-WATER-12');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-FOOD-01', '常温食品综合包', 'FOOD', '包', 0, 60, 0, 12
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-FOOD-01');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-CARE-01', '婴幼儿基础护理包', 'HYGIENE', '包', 0, 35, 0, 7
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-CARE-01');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-MEDICAL-01', '基础医疗用品包', 'MEDICAL', '包', 0, 35, 0, 7
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-MEDICAL-01');
