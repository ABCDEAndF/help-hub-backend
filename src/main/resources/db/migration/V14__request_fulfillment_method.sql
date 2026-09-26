-- Residents choose between collecting at a service point and mobile-cart delivery.
-- Existing requests were all eligible for cart routing, so they keep that behaviour.
ALTER TABLE supply_requests
    ADD COLUMN fulfillment_method ENUM('PICKUP', 'DELIVERY') NOT NULL DEFAULT 'DELIVERY' AFTER urgency;
