ALTER TABLE asset
    DROP CHECK ck_asset_purpose,
    ADD CONSTRAINT ck_asset_purpose CHECK (
        purpose IS NULL OR purpose IN (
            'CATEGORY_ICON', 'WALLPAPER_COVER', 'BACKGROUND', 'FOREGROUND',
            'PARALLAX_CONFIG', 'VIDEO', 'TUTORIAL_VIDEO', 'LIVE_PHOTO_IMAGE',
            'LIVE_PHOTO_VIDEO', 'LIVE_PHOTO_SOURCE', 'STATIC_IMAGE',
            'THEME_PACKAGE', 'MOVING_PHOTO_SOURCE'
        )
    );

ALTER TABLE resource_binding
    DROP CHECK ck_binding_role,
    ADD CONSTRAINT ck_binding_role CHECK (
        role IN (
            'COVER', 'BACKGROUND', 'FOREGROUND', 'PARALLAX_CONFIG', 'VIDEO',
            'LIVE_PHOTO_IMAGE', 'LIVE_PHOTO_VIDEO', 'LIVE_PHOTO_SOURCE',
            'STATIC_IMAGE', 'THEME_PACKAGE', 'MOVING_PHOTO_SOURCE'
        )
    );

ALTER TABLE moving_photo_package
    ADD COLUMN input_video_codec VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER height_px,
    ADD COLUMN output_video_codec VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER input_video_codec,
    ADD COLUMN frame_rate DECIMAL(8,3) NULL AFTER output_video_codec,
    ADD COLUMN processing_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER frame_rate,
    ADD CONSTRAINT ck_moving_photo_processing_mode CHECK (
        processing_mode IS NULL OR processing_mode IN ('PASSTHROUGH', 'REMUX', 'TRANSCODE')
    );

UPDATE moving_photo_package
SET input_video_codec='h264', output_video_codec='h264', frame_rate=30.000,
    processing_mode='TRANSCODE'
WHERE status='READY' AND processing_mode IS NULL;

UPDATE moving_photo_package
SET status='REJECTED', error_code='MOVING_PHOTO_PROCESSING_FAILED'
WHERE status='READY' AND duration_ms <> 2000;

ALTER TABLE moving_photo_package
    DROP CHECK ck_moving_photo_ready,
    ADD CONSTRAINT ck_moving_photo_ready CHECK (
        status <> 'READY' OR (
            video_storage_key IS NOT NULL AND video_size_bytes > 0
            AND video_sha256 REGEXP '^[a-f0-9]{64}$'
            AND poster_storage_key IS NOT NULL AND poster_size_bytes > 0
            AND poster_sha256 REGEXP '^[a-f0-9]{64}$'
            AND duration_ms = 2000
            AND width_px > 0 AND height_px > 0
            AND input_video_codec IN ('h264', 'hevc')
            AND output_video_codec IN ('h264', 'hevc')
            AND frame_rate > 0 AND frame_rate <= 60
            AND processing_mode IN ('PASSTHROUGH', 'REMUX', 'TRANSCODE')
            AND error_code IS NULL
        )
    );

CREATE TABLE live_photo_package (
    resource_version_id BIGINT NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    photo_storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    photo_size_bytes BIGINT NULL,
    photo_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    video_storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    video_size_bytes BIGINT NULL,
    video_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    asset_identifier CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    duration_ms BIGINT NULL,
    width_px INT NULL,
    height_px INT NULL,
    input_video_codec VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    output_video_codec VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    frame_rate DECIMAL(8,3) NULL,
    processing_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_live_photo_package PRIMARY KEY (resource_version_id),
    CONSTRAINT fk_live_photo_version FOREIGN KEY (resource_version_id)
        REFERENCES resource_version (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT uk_live_photo_photo_storage UNIQUE (photo_storage_key),
    CONSTRAINT uk_live_photo_video_storage UNIQUE (video_storage_key),
    CONSTRAINT uk_live_photo_asset_identifier UNIQUE (asset_identifier),
    CONSTRAINT ck_live_photo_status CHECK (status IN ('PROCESSING', 'READY', 'REJECTED')),
    CONSTRAINT ck_live_photo_processing_mode CHECK (
        processing_mode IS NULL OR processing_mode IN ('PASSTHROUGH', 'REMUX', 'TRANSCODE')
    ),
    CONSTRAINT ck_live_photo_ready CHECK (
        status <> 'READY' OR (
            photo_storage_key IS NOT NULL AND photo_size_bytes > 0
            AND photo_sha256 REGEXP '^[a-f0-9]{64}$'
            AND video_storage_key IS NOT NULL AND video_size_bytes > 0
            AND video_sha256 REGEXP '^[a-f0-9]{64}$'
            AND asset_identifier REGEXP '^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$'
            AND duration_ms = 1000
            AND width_px > 0 AND height_px > 0
            AND input_video_codec IN ('h264', 'hevc')
            AND output_video_codec IN ('h264', 'hevc')
            AND frame_rate > 0 AND frame_rate <= 60
            AND processing_mode IN ('PASSTHROUGH', 'REMUX', 'TRANSCODE')
            AND error_code IS NULL
        )
    ),
    CONSTRAINT ck_live_photo_error CHECK (status <> 'REJECTED' OR error_code IS NOT NULL),
    INDEX ix_live_photo_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
