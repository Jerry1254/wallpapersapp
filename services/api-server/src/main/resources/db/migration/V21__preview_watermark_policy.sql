ALTER TABLE wallpaper ADD COLUMN preview_watermark_enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE wallpaper_preview_state (
    wallpaper_id BIGINT NOT NULL PRIMARY KEY,
    requested_revision BIGINT NOT NULL DEFAULT 1,
    generated_revision BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    generation_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    started_at DATETIME(6) NULL,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cover_storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cover_size_bytes BIGINT NULL,
    cover_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cover_mime_type VARCHAR(64) NULL,
    cover_is_derived BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY ix_preview_generation (status, updated_at),
    CONSTRAINT fk_preview_state_wallpaper FOREIGN KEY (wallpaper_id) REFERENCES wallpaper(id) ON DELETE CASCADE,
    CONSTRAINT ck_preview_generation_status CHECK (status IN ('PENDING','PROCESSING','READY','FAILED')),
    CONSTRAINT ck_preview_revision CHECK (requested_revision > 0 AND generated_revision >= 0 AND generated_revision <= requested_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE preview_resource_package ADD COLUMN preview_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE preview_media (
    resource_version_id BIGINT NOT NULL PRIMARY KEY,
    preview_revision BIGINT NOT NULL,
    storage_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mime_type VARCHAR(64) NOT NULL,
    duration_ms BIGINT NOT NULL,
    is_derived BOOLEAN NOT NULL,
    CONSTRAINT fk_preview_media_version FOREIGN KEY (resource_version_id) REFERENCES resource_version(id) ON DELETE RESTRICT,
    CONSTRAINT ck_preview_media_size CHECK (size_bytes > 0),
    CONSTRAINT ck_preview_media_revision CHECK (preview_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Durable, resumable backfill. No source assets, downloads or entitlements are rewritten.
INSERT INTO wallpaper_preview_state (wallpaper_id)
SELECT id FROM wallpaper WHERE status <> 'ARCHIVED';
