ALTER TABLE anonymous_device
    ADD COLUMN last_active_at DATETIME(6) NULL,
    ADD COLUMN app_version_name VARCHAR(64) NULL,
    ADD COLUMN app_version_code VARCHAR(32) NULL,
    ADD COLUMN device_manufacturer VARCHAR(64) NULL,
    ADD COLUMN device_model VARCHAR(96) NULL,
    ADD COLUMN device_os_version VARCHAR(96) NULL,
    ADD COLUMN operator_note VARCHAR(500) NOT NULL DEFAULT '',
    ADD COLUMN operator_note_version BIGINT NOT NULL DEFAULT 0,
    ADD INDEX ix_device_created (created_at, id),
    ADD INDEX ix_device_activity (last_active_at, id);

CREATE TABLE device_activity_daily (
    activity_date DATE NOT NULL,
    device_id BIGINT NOT NULL,
    first_active_at DATETIME(6) NOT NULL,
    last_active_at DATETIME(6) NOT NULL,
    PRIMARY KEY (activity_date, device_id),
    INDEX ix_daily_activity_device (device_id, activity_date),
    FOREIGN KEY (device_id) REFERENCES anonymous_device(id),
    CHECK (last_active_at >= first_active_at)
) ENGINE=InnoDB;

-- No historic DAU can be reconstructed from the old last-session timestamp.
CREATE TABLE operations_tracking (
    id TINYINT NOT NULL PRIMARY KEY,
    started_at DATETIME(6) NOT NULL,
    CHECK (id = 1)
) ENGINE=InnoDB;
INSERT INTO operations_tracking(id, started_at) VALUES (1, UTC_TIMESTAMP(6));

-- Free downloads also need a fact independent of device_entitlement.
CREATE TABLE device_download_request_event (
    ticket_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    device_id BIGINT NOT NULL,
    wallpaper_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (device_id) REFERENCES anonymous_device(id),
    FOREIGN KEY (wallpaper_id) REFERENCES wallpaper(id),
    INDEX ix_device_download_created (created_at, device_id),
    INDEX ix_device_download_device (device_id, created_at)
) ENGINE=InnoDB;

CREATE INDEX ix_ios_credit_order_device ON ios_credit_order(device_id, created_at, id);
CREATE INDEX ix_ios_credit_installation_device ON ios_credit_order_installation(device_id, order_id);
CREATE INDEX ix_ios_purchase_device ON ios_purchase_installation(device_id, environment, bundle_id, original_transaction_id);
