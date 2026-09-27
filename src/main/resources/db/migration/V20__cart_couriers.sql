-- Each cart is driven by one courier, who sees that cart's route in the operator console.
-- Couriers are bound through the admin API, never here, because this repository is public.
ALTER TABLE mobile_carts
    ADD COLUMN courier_id BIGINT NULL AFTER capacity_units,
    ADD CONSTRAINT fk_cart_courier FOREIGN KEY (courier_id) REFERENCES users(id),
    ADD CONSTRAINT uk_cart_courier UNIQUE (courier_id);

INSERT INTO mobile_carts
    (code, name, capacity_units, status, latitude, longitude, last_location_at)
SELECT 'QP-CART-003', '青浦公益补给车三号', 100, 'AVAILABLE', 31.1518500, 121.1294200,
       CURRENT_TIMESTAMP(3)
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM mobile_carts WHERE code = 'QP-CART-003');
