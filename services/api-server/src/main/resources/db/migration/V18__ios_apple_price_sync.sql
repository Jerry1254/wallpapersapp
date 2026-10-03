-- Previously entered amounts are not authoritative Apple prices.
ALTER TABLE ios_product_mapping
    ADD COLUMN apple_in_app_purchase_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN apple_price_point_id VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN price_sync_status VARCHAR(16) NOT NULL DEFAULT 'UNSYNCED',
    ADD COLUMN price_synced_at DATETIME(6) NULL,
    ADD COLUMN price_sync_error VARCHAR(40) NULL,
    ADD COLUMN price_sync_next_at DATETIME(6) NULL,
    ADD COLUMN price_sync_lease_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN price_sync_lease_until DATETIME(6) NULL,
    ADD CONSTRAINT ck_ios_price_sync_status CHECK (price_sync_status IN ('UNSYNCED','READY','ERROR')),
    ADD INDEX ix_ios_price_sync_due (bundle_id,enabled,price_sync_next_at);
UPDATE ios_product_mapping SET china_reference_price=NULL;
