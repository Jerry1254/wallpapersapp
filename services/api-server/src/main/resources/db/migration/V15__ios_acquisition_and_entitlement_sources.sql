ALTER TABLE device_entitlement
    MODIFY source_code_id BIGINT NULL;

CREATE TABLE ios_product_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    wallpaper_id BIGINT NOT NULL,
    bundle_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NON_CONSUMABLE',
    first_free_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    verified_transaction_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_ios_product_mapping PRIMARY KEY (id),
    CONSTRAINT uk_ios_product_wallpaper UNIQUE (bundle_id, wallpaper_id),
    CONSTRAINT uk_ios_product_id UNIQUE (bundle_id, product_id),
    CONSTRAINT fk_ios_product_wallpaper FOREIGN KEY (wallpaper_id)
        REFERENCES wallpaper (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_ios_product_type CHECK (product_type = 'NON_CONSUMABLE'),
    INDEX ix_ios_product_enabled (bundle_id, enabled, wallpaper_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ios_installation_acquisition (
    device_id BIGINT NOT NULL,
    account_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    free_generation BIGINT NOT NULL DEFAULT 0,
    free_allowance_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNAVAILABLE',
    is_test_device BOOLEAN NOT NULL DEFAULT FALSE,
    active_reset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    devicecheck_checked_at DATETIME(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_ios_installation_acquisition PRIMARY KEY (device_id),
    CONSTRAINT uk_ios_installation_account_token UNIQUE (account_token),
    CONSTRAINT fk_ios_installation_device FOREIGN KEY (device_id)
        REFERENCES anonymous_device (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_ios_installation_generation CHECK (free_generation >= 0),
    CONSTRAINT ck_ios_installation_allowance CHECK (
        free_allowance_status IN ('AVAILABLE', 'USED', 'UNAVAILABLE', 'PENDING_RESET')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ios_app_attest_key (
    id BIGINT NOT NULL AUTO_INCREMENT,
    device_id BIGINT NOT NULL,
    key_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    public_key_pem TEXT NOT NULL,
    app_id_prefix VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bundle_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt MEDIUMBLOB NULL,
    assertion_counter BIGINT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_ios_app_attest_key PRIMARY KEY (id),
    CONSTRAINT uk_ios_app_attest_key UNIQUE (key_id),
    CONSTRAINT fk_ios_app_attest_device FOREIGN KEY (device_id)
        REFERENCES anonymous_device (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_ios_app_attest_environment CHECK (environment IN ('DEVELOPMENT', 'PRODUCTION')),
    CONSTRAINT ck_ios_app_attest_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    INDEX ix_ios_app_attest_device (device_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ios_attestation_challenge (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_id BIGINT NOT NULL,
    key_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    wallpaper_id BIGINT NULL,
    reset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    free_generation BIGINT NOT NULL,
    nonce VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_data TEXT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_ios_attestation_challenge PRIMARY KEY (id),
    CONSTRAINT fk_ios_challenge_device FOREIGN KEY (device_id)
        REFERENCES anonymous_device (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ios_challenge_wallpaper FOREIGN KEY (wallpaper_id)
        REFERENCES wallpaper (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_ios_challenge_action CHECK (
        action IN ('ENROLL', 'STATUS', 'FREE_CLAIM', 'PURCHASE_SYNC', 'FREE_RESET')
    ),
    INDEX ix_ios_challenge_expiry (device_id, consumed_at, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ios_free_claim (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_id BIGINT NOT NULL,
    free_generation BIGINT NOT NULL,
    wallpaper_id BIGINT NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_token_encrypted TEXT NULL,
    apple_transaction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    entitlement_id BIGINT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    active_generation BIGINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PROCESSING', 'APPLE_CONFIRMED', 'COMPLETED') THEN free_generation ELSE NULL END
    ) STORED,
    CONSTRAINT pk_ios_free_claim PRIMARY KEY (id),
    CONSTRAINT uk_ios_free_claim_request UNIQUE (device_id, request_id),
    CONSTRAINT uk_ios_free_claim_generation UNIQUE (device_id, active_generation),
    CONSTRAINT fk_ios_free_claim_device FOREIGN KEY (device_id)
        REFERENCES anonymous_device (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ios_free_claim_wallpaper FOREIGN KEY (wallpaper_id)
        REFERENCES wallpaper (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ios_free_claim_entitlement FOREIGN KEY (entitlement_id)
        REFERENCES device_entitlement (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_ios_free_claim_status CHECK (
        status IN ('PROCESSING', 'APPLE_CONFIRMED', 'COMPLETED', 'REJECTED', 'RESET')
    ),
    INDEX ix_ios_free_claim_recovery (status, updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ios_store_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bundle_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    original_transaction_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    app_account_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    device_verification_id VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NULL,
    wallpaper_id BIGINT NOT NULL,
    purchased_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    verified_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_ios_store_transaction PRIMARY KEY (id),
    CONSTRAINT uk_ios_store_transaction UNIQUE (environment, transaction_id),
    CONSTRAINT fk_ios_store_transaction_wallpaper FOREIGN KEY (wallpaper_id)
        REFERENCES wallpaper (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_ios_store_environment CHECK (environment IN ('SANDBOX', 'PRODUCTION', 'XCODE')),
    INDEX ix_ios_store_original (environment, original_transaction_id),
    INDEX ix_ios_store_product (bundle_id, product_id, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE entitlement_grant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    entitlement_id BIGINT NOT NULL,
    source_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_reference VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    free_generation BIGINT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
    granted_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    revoke_reason VARCHAR(300) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_entitlement_grant PRIMARY KEY (id),
    CONSTRAINT uk_entitlement_grant_source UNIQUE (source_type, source_reference),
    CONSTRAINT fk_entitlement_grant_entitlement FOREIGN KEY (entitlement_id)
        REFERENCES device_entitlement (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_entitlement_grant_source CHECK (
        source_type IN ('REDEMPTION', 'IOS_FIRST_FREE', 'IOS_IAP')
    ),
    CONSTRAINT ck_entitlement_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_entitlement_grant_revoke_shape CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL AND revoke_reason IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    ),
    INDEX ix_entitlement_grant_entitlement (entitlement_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO entitlement_grant
    (entitlement_id, source_type, source_reference, status, granted_at, revoked_at, revoke_reason)
SELECT id, 'REDEMPTION', CAST(source_code_id AS CHAR), status, granted_at, revoked_at, revoke_reason
FROM device_entitlement
WHERE source_code_id IS NOT NULL;

CREATE TABLE ios_free_reset (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_id BIGINT NOT NULL,
    expected_generation BIGINT NOT NULL,
    result_generation BIGINT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_by_admin_id BIGINT NOT NULL,
    reason VARCHAR(300) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    device_token_encrypted TEXT NULL,
    apple_transaction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    expires_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_ios_free_reset PRIMARY KEY (id),
    CONSTRAINT uk_ios_free_reset_idempotency UNIQUE (device_id, idempotency_key),
    CONSTRAINT fk_ios_free_reset_device FOREIGN KEY (device_id)
        REFERENCES anonymous_device (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_ios_free_reset_admin FOREIGN KEY (created_by_admin_id)
        REFERENCES admin_account (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_ios_free_reset_status CHECK (
        status IN ('WAITING_DEVICE', 'PROCESSING', 'APPLE_RESET_CONFIRMED', 'COMPLETED',
                   'EXPIRED', 'CANCELLED', 'RETRYABLE_FAILURE')
    ),
    INDEX ix_ios_free_reset_recovery (status, updated_at, id),
    INDEX ix_ios_free_reset_device (device_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE apple_notification_inbox (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    notification_uuid CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    environment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    notification_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    subtype VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    signed_payload MEDIUMTEXT NOT NULL,
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RECEIVED',
    attempts INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at DATETIME(6) NULL,
    CONSTRAINT pk_apple_notification_inbox PRIMARY KEY (id),
    CONSTRAINT uk_apple_notification_uuid UNIQUE (notification_uuid),
    CONSTRAINT ck_apple_notification_status CHECK (
        status IN ('RECEIVED', 'VERIFIED', 'PROCESSED', 'RETRYABLE_FAILURE', 'REJECTED')
    ),
    INDEX ix_apple_notification_recovery (status, received_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
