-- Stable lock rows serialize publication/withdrawal independently for each platform.
CREATE TABLE app_release_platform (
    platform VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    CONSTRAINT ck_app_release_platform CHECK (platform IN ('android', 'ios', 'harmony'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO app_release_platform (platform) VALUES ('android'), ('ios'), ('harmony');

CREATE TABLE app_release (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    platform VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_code BIGINT NOT NULL,
    version_identity VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    release_notes VARCHAR(1000) NOT NULL,
    force_update BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
    delivery_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    store_url VARCHAR(1000) NULL,
    storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    package_name VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    file_size BIGINT NULL,
    abi VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    signer_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    min_sdk_version INT NULL,
    created_by_admin_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    deprecated_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_app_release_platform FOREIGN KEY (platform) REFERENCES app_release_platform (platform),
    CONSTRAINT fk_app_release_admin FOREIGN KEY (created_by_admin_id) REFERENCES admin_account (id),
    -- Android/Harmony identity is versionCode; iOS identity is normalized marketing version.
    -- Apple permits the build counter to restart in a new marketing version.
    CONSTRAINT uk_app_release_identity UNIQUE (platform, version_identity),
    CONSTRAINT uk_app_release_storage UNIQUE (storage_key),
    CONSTRAINT ck_app_release_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED')),
    CONSTRAINT ck_app_release_code CHECK (version_code > 0 AND version_code <= 9007199254740991),
    CONSTRAINT ck_app_release_shape CHECK (
        (platform = 'android' AND delivery_type = 'apk' AND store_url IS NULL
          AND storage_key IS NOT NULL AND package_name IS NOT NULL
          AND sha256 IS NOT NULL AND file_size > 0 AND abi IS NOT NULL
          AND signer_sha256 IS NOT NULL AND min_sdk_version > 0)
        OR (platform IN ('ios', 'harmony') AND delivery_type = 'store'
          AND store_url IS NOT NULL AND storage_key IS NULL AND sha256 IS NULL
          AND file_size IS NULL AND signer_sha256 IS NULL)
    ),
    INDEX ix_app_release_policy (platform, status, force_update, version_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
