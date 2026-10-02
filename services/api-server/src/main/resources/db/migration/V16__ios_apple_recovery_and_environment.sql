-- V15 is immutable. Provider write phases preserve ambiguous outcomes across restarts.
ALTER TABLE ios_free_claim DROP CHECK ck_ios_free_claim_status;
ALTER TABLE ios_free_claim
    ADD CONSTRAINT ck_ios_free_claim_status CHECK (status IN
        ('PROCESSING','APPLE_WRITE_STARTED','RECONCILING','APPLE_CONFIRMED','COMPLETED','REJECTED','RESET'));
ALTER TABLE ios_free_claim DROP INDEX uk_ios_free_claim_generation;
ALTER TABLE ios_free_claim DROP COLUMN active_generation;
ALTER TABLE ios_free_claim
    ADD COLUMN active_generation BIGINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PROCESSING','APPLE_WRITE_STARTED','RECONCILING','APPLE_CONFIRMED','COMPLETED')
        THEN free_generation ELSE NULL END) STORED,
    ADD CONSTRAINT uk_ios_free_claim_generation UNIQUE (device_id, active_generation);

ALTER TABLE ios_store_transaction
    DROP INDEX uk_ios_store_transaction,
    ADD CONSTRAINT uk_ios_store_transaction UNIQUE (environment,bundle_id,transaction_id),
    ADD COLUMN signed_at DATETIME(6) NULL,
    ADD COLUMN app_transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE entitlement_grant ADD COLUMN apple_environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE apple_notification_inbox ADD COLUMN next_attempt_at DATETIME(6) NULL;

CREATE TABLE ios_purchase_installation (
    environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bundle_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_id BIGINT NOT NULL,
    source_reference CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT pk_ios_purchase_installation PRIMARY KEY (environment,bundle_id,original_transaction_id,device_id),
    CONSTRAINT uk_ios_purchase_source UNIQUE (source_reference),
    CONSTRAINT fk_ios_purchase_device FOREIGN KEY (device_id) REFERENCES anonymous_device(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Keep all committed provider outcomes available for compensation and operator diagnosis.
CREATE TABLE ios_acquisition_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NULL,
    operation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_ios_audit_operation (operation_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
