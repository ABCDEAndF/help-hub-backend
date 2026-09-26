-- More everyday essentials and urgent supplies at the Qingpu service points.
-- Guarded like V10/V11 so a repeated or recovered run never duplicates stock.

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-FLOUR-5KG', '公益面粉 5 千克', 'FOOD', '袋', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-FLOUR-5KG');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-FLOUR-5KG', '公益面粉 5 千克', 'FOOD', '袋', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-FLOUR-5KG');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-MILK-12', '公益纯牛奶 250 毫升×12 盒', 'FOOD', '箱', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-MILK-12');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-MILK-12', '公益纯牛奶 250 毫升×12 盒', 'FOOD', '箱', 0, 50, 0, 10
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-MILK-12');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-NOODLE-INSTANT-5', '公益方便面 5 连包', 'FOOD', '提', 0, 60, 0, 12
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-NOODLE-INSTANT-5');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-NOODLE-INSTANT-5', '公益方便面 5 连包', 'FOOD', '提', 0, 60, 0, 12
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-NOODLE-INSTANT-5');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-OATS-1KG', '公益燕麦片 1 千克', 'FOOD', '袋', 0, 30, 0, 6
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-OATS-1KG');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-SALT-400G', '公益食盐 400 克', 'FOOD', '袋', 0, 60, 0, 12
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-SALT-400G');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-SALT-400G', '公益食盐 400 克', 'FOOD', '袋', 0, 60, 0, 12
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-SALT-400G');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-SOY-500ML', '公益酱油 500 毫升', 'FOOD', '瓶', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-SOY-500ML');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-CANNED-MEAT', '公益午餐肉罐头 340 克', 'FOOD', '罐', 0, 50, 0, 10
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-CANNED-MEAT');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-CRACKERS-1KG', '公益苏打饼干 1 千克', 'FOOD', '箱', 0, 30, 0, 6
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-CRACKERS-1KG');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-WATER-SMALL-24', '公益饮用水 550 毫升×24 瓶', 'WATER', '箱', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-WATER-SMALL-24');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-DETERGENT-2KG', '洗衣液 2 千克', 'HYGIENE', '瓶', 0, 30, 0, 6
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-DETERGENT-2KG');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-DETERGENT-2KG', '洗衣液 2 千克', 'HYGIENE', '瓶', 0, 30, 0, 6
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-DETERGENT-2KG');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-SOAP-3', '香皂 3 块装', 'HYGIENE', '组', 0, 50, 0, 10
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-SOAP-3');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-TOOTH-KIT', '牙膏牙刷套装', 'HYGIENE', '套', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-TOOTH-KIT');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-TOOTH-KIT', '牙膏牙刷套装', 'HYGIENE', '套', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-TOOTH-KIT');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-PADS', '卫生巾日夜组合装', 'HYGIENE', '包', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-PADS');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-ADULT-DIAPER-L', '成人纸尿裤 L 码', 'HYGIENE', '包', 0, 25, 0, 5
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-ADULT-DIAPER-L');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-BABY-DIAPER-M', '婴儿纸尿裤 M 码', 'HYGIENE', '包', 0, 25, 0, 5
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-BABY-DIAPER-M');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-MASK-50', '医用外科口罩 50 只', 'MEDICAL', '盒', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-MASK-50');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-MASK-50', '医用外科口罩 50 只', 'MEDICAL', '盒', 0, 40, 0, 8
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-MASK-50');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-THERMOMETER', '电子体温计', 'MEDICAL', '支', 0, 20, 0, 4
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-THERMOMETER');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-ALCOHOL-500ML', '酒精消毒液 75% 500 毫升', 'MEDICAL', '瓶', 0, 30, 0, 6
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-ALCOHOL-500ML');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-BLANKET', '保暖毯', 'OTHER', '条', 0, 25, 0, 5
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-BLANKET');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-XY-RAINCOAT', '一次性雨衣', 'OTHER', '件', 0, 50, 0, 10
FROM service_points
WHERE name = '邻需通·夏阳公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-XY-RAINCOAT');

INSERT INTO inventory_items
    (service_point_id, sku, name, category, unit, unit_price_fen,
     available_quantity, reserved_quantity, reorder_threshold)
SELECT id, 'QP-GY-CANDLE-LIGHTER', '应急蜡烛与打火机套装', 'OTHER', '套', 0, 30, 0, 6
FROM service_points
WHERE name = '邻需通·青浦工业园区公益服务点'
  AND NOT EXISTS (SELECT 1 FROM inventory_items WHERE sku = 'QP-GY-CANDLE-LIGHTER');
