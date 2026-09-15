ALTER TABLE asset
    ADD COLUMN purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER file_extension,
    ADD CONSTRAINT ck_asset_purpose CHECK (
        purpose IS NULL OR purpose IN (
            'CATEGORY_ICON', 'WALLPAPER_COVER', 'BACKGROUND', 'FOREGROUND',
            'PARALLAX_CONFIG', 'VIDEO', 'TUTORIAL_VIDEO', 'LIVE_PHOTO_IMAGE',
            'LIVE_PHOTO_VIDEO', 'STATIC_IMAGE', 'THEME_PACKAGE'
        )
    );

CREATE TABLE wallpaper_setting_tutorial (
    tutorial_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    video_asset_id BIGINT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT pk_wallpaper_setting_tutorial PRIMARY KEY (tutorial_key),
    CONSTRAINT fk_wallpaper_setting_tutorial_video FOREIGN KEY (video_asset_id)
        REFERENCES asset (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT ck_wallpaper_setting_tutorial_key CHECK (tutorial_key IN (
        'ANDROID_PARALLAX_4D', 'ANDROID_DYNAMIC', 'STATIC', 'HARMONYOS_DYNAMIC', 'IOS_DYNAMIC'
    )),
    CONSTRAINT ck_wallpaper_setting_tutorial_enabled CHECK (enabled = FALSE OR video_asset_id IS NOT NULL),
    CONSTRAINT ck_wallpaper_setting_tutorial_sort CHECK (sort_order BETWEEN 0 AND 9999),
    CONSTRAINT ck_wallpaper_setting_tutorial_version CHECK (lock_version >= 0),
    INDEX ix_wallpaper_setting_tutorial_public (enabled, sort_order, tutorial_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO wallpaper_setting_tutorial (tutorial_key, sort_order) VALUES
    ('ANDROID_PARALLAX_4D', 10),
    ('ANDROID_DYNAMIC', 20),
    ('STATIC', 30),
    ('HARMONYOS_DYNAMIC', 40),
    ('IOS_DYNAMIC', 50);

ALTER TABLE audit_event
    DROP CHECK ck_audit_event_aggregate_type,
    ADD CONSTRAINT ck_audit_event_aggregate_type CHECK (
        aggregate_type IN (
            'ADMIN_ACCOUNT', 'ASSET', 'CATEGORY', 'WALLPAPER', 'WALLPAPER_VARIANT',
            'RESOURCE_VERSION', 'WALLPAPER_TUTORIAL', 'CODE_BATCH', 'REDEMPTION_CODE',
            'ANONYMOUS_DEVICE', 'DEVICE_ENTITLEMENT', 'DOWNLOAD_TICKET', 'SYSTEM'
        )
    );
