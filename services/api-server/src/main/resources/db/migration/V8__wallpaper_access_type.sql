ALTER TABLE wallpaper
    ADD COLUMN access_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
        NOT NULL DEFAULT 'REDEEM' AFTER kind,
    ADD CONSTRAINT ck_wallpaper_access_type CHECK (access_type IN ('REDEEM', 'FREE')),
    ADD INDEX ix_wallpaper_public_access (status, access_type, sort_order, id);

ALTER TABLE redemption_event
    DROP CHECK ck_redemption_event_result,
    ADD CONSTRAINT ck_redemption_event_result CHECK (
        result IN (
            'GRANTED',
            'ALREADY_OWNED',
            'CODE_NOT_FOUND',
            'CODE_EXHAUSTED',
            'WALLPAPER_UNAVAILABLE',
            'WALLPAPER_FREE',
            'FAILED'
        )
    );
