-- Each resident's own random default location inside the service area, assigned on first use and
-- used for requests sent without picking a place.
ALTER TABLE users
    ADD COLUMN home_latitude DECIMAL(10, 7) NULL,
    ADD COLUMN home_longitude DECIMAL(10, 7) NULL;
