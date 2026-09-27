ALTER TABLE asset
    DROP CHECK ck_asset_purpose,
    ADD CONSTRAINT ck_asset_purpose CHECK (
        purpose IS NULL OR purpose IN (
            'CATEGORY_ICON', 'WALLPAPER_COVER', 'BACKGROUND', 'FOREGROUND',
            'PARALLAX_CONFIG', 'VIDEO', 'TUTORIAL_VIDEO', 'LIVE_PHOTO_IMAGE',
            'LIVE_PHOTO_VIDEO', 'STATIC_IMAGE', 'THEME_PACKAGE', 'MOVING_PHOTO_SOURCE'
        )
    );

ALTER TABLE wallpaper_variant
    DROP CHECK ck_variant_resource_type,
    ADD CONSTRAINT ck_variant_resource_type CHECK (
        resource_type IN (
            'LAYER_PARALLAX', 'VIDEO', 'LIVE_PHOTO', 'STATIC_IMAGE',
            'THEME_PACKAGE', 'MOVING_PHOTO'
        )
    );

ALTER TABLE resource_binding
    DROP CHECK ck_binding_role,
    ADD CONSTRAINT ck_binding_role CHECK (
        role IN (
            'COVER', 'BACKGROUND', 'FOREGROUND', 'PARALLAX_CONFIG', 'VIDEO',
            'LIVE_PHOTO_IMAGE', 'LIVE_PHOTO_VIDEO', 'STATIC_IMAGE',
            'THEME_PACKAGE', 'MOVING_PHOTO_SOURCE'
        )
    );

CREATE TABLE moving_photo_package (
    resource_version_id BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    video_storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    video_size_bytes BIGINT NULL,
    video_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    poster_storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    poster_size_bytes BIGINT NULL,
    poster_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    duration_ms BIGINT NULL,
    width_px INT NULL,
    height_px INT NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_moving_photo_package PRIMARY KEY (resource_version_id),
    CONSTRAINT fk_moving_photo_version FOREIGN KEY (resource_version_id)
        REFERENCES resource_version (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT uk_moving_photo_video_storage UNIQUE (video_storage_key),
    CONSTRAINT uk_moving_photo_poster_storage UNIQUE (poster_storage_key),
    CONSTRAINT ck_moving_photo_status CHECK (status IN ('PROCESSING', 'READY', 'REJECTED')),
    CONSTRAINT ck_moving_photo_ready CHECK (
        status <> 'READY' OR (
            video_storage_key IS NOT NULL AND video_size_bytes > 0
            AND video_sha256 REGEXP '^[a-f0-9]{64}$'
            AND poster_storage_key IS NOT NULL AND poster_size_bytes > 0
            AND poster_sha256 REGEXP '^[a-f0-9]{64}$'
            AND duration_ms BETWEEN 1 AND 2000
            AND width_px > 0 AND height_px > 0
            AND error_code IS NULL
        )
    ),
    CONSTRAINT ck_moving_photo_error CHECK (status <> 'REJECTED' OR error_code IS NOT NULL),
    INDEX ix_moving_photo_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
