CREATE TABLE device_capability_profile (
    device_id BIGINT NOT NULL,
    host_os_family VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    host_os_version VARCHAR(32) NOT NULL,
    sdk_int INT NULL,
    manufacturer VARCHAR(64) NOT NULL,
    model VARCHAR(96) NOT NULL,
    execution_mode VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    probe_version INT NOT NULL,
    feature_flags JSON NOT NULL,
    reported_capabilities JSON NOT NULL,
    effective_capabilities JSON NOT NULL,
    profile_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    probed_at DATETIME(6) NOT NULL,
    last_verified_at DATETIME(6) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_device_capability_profile PRIMARY KEY (device_id),
    CONSTRAINT fk_device_capability_profile_device FOREIGN KEY (device_id) REFERENCES anonymous_device (id) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT ck_device_capability_host_os CHECK (host_os_family IN ('ANDROID', 'EMUI', 'HARMONY_CLASSIC', 'HARMONY_NATIVE', 'IOS')),
    CONSTRAINT ck_device_capability_execution_mode CHECK (execution_mode IN ('NATIVE', 'ANDROID_COMPATIBLE', 'ANDROID_CONTAINER')),
    CONSTRAINT ck_device_capability_sdk CHECK (sdk_int IS NULL OR sdk_int BETWEEN 1 AND 10000),
    CONSTRAINT ck_device_capability_probe CHECK (probe_version BETWEEN 1 AND 2147483647),
    CONSTRAINT ck_device_capability_features CHECK (JSON_TYPE(feature_flags) = 'ARRAY'),
    CONSTRAINT ck_device_capability_reported CHECK (JSON_TYPE(reported_capabilities) = 'ARRAY'),
    CONSTRAINT ck_device_capability_effective CHECK (JSON_TYPE(effective_capabilities) = 'ARRAY'),
    CONSTRAINT ck_device_capability_hash CHECK (profile_hash REGEXP '^[a-f0-9]{64}$'),
    CONSTRAINT ck_device_capability_lock_version CHECK (lock_version >= 0),
    INDEX ix_device_capability_hash (profile_hash),
    INDEX ix_device_capability_updated (updated_at, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE wallpaper_variant
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER capability_requirements;

ALTER TABLE wallpaper DROP CHECK ck_wallpaper_kind;
ALTER TABLE wallpaper DROP INDEX ix_wallpaper_public_kind;
ALTER TABLE wallpaper DROP COLUMN kind;
