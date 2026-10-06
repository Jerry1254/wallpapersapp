ALTER TABLE creator_social_account
    ADD COLUMN platform_user_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN nickname VARCHAR(160) NOT NULL DEFAULT '',
    ADD COLUMN avatar_url VARCHAR(2000) NOT NULL DEFAULT '',
    ADD COLUMN identity_checked_at DATETIME(3) NULL,
    ADD COLUMN active_identity VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN archived=FALSE THEN platform_user_id ELSE NULL END) STORED,
    ADD UNIQUE KEY uq_creator_social_identity (platform, active_identity);

UPDATE creator_social_account SET login_status='unverified' WHERE login_status='ready';

ALTER TABLE creator_publish_media
    ADD COLUMN workspace_media_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uq_creator_publish_workspace_media (workspace_media_id),
    ADD CONSTRAINT fk_creator_publish_workspace_media FOREIGN KEY (workspace_media_id) REFERENCES creator_workspace_media(id);
