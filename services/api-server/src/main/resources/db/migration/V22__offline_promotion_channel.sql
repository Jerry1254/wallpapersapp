-- Existing catalog stays online-visible; promotion-only visibility is an explicit administrator choice.
ALTER TABLE wallpaper ADD COLUMN offline_promotion_only BOOLEAN NOT NULL DEFAULT FALSE;
