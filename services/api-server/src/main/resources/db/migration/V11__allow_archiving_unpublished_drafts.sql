ALTER TABLE wallpaper
    DROP CHECK ck_wallpaper_published_at,
    ADD CONSTRAINT ck_wallpaper_published_at CHECK (
        status NOT IN ('PUBLISHED', 'OFFLINE') OR published_at IS NOT NULL
    );
