-- The CloudRun template database defaults to 3-byte utf8, which cannot store emoji. A model
-- reply containing one made every assistant turn fail, and residents typing emoji in a
-- request, feedback or their name would fail the same way. Convert every table holding
-- user- or model-written text to utf8mb4 (a no-op where it already is).
ALTER TABLE users CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE service_points CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE mobile_carts CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE inventory_items CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE supply_requests CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE route_plans CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE service_feedback CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- assistant_messages.conversation_id references the string key assistant_conversations.id;
-- both sides must change together, so checks are paused for this session only.
SET FOREIGN_KEY_CHECKS = 0;
ALTER TABLE assistant_conversations CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE assistant_messages CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SET FOREIGN_KEY_CHECKS = 1;
ALTER TABLE audit_log CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
