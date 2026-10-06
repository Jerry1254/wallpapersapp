-- 创作台仅负责调用壁纸业务，不保存编辑器项目、素材库或渲染任务。
CREATE TABLE creator_wallpaper_upload (
    environment_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id BIGINT NULL,
    PRIMARY KEY (environment_id, idempotency_key),
    CONSTRAINT fk_creator_upload_asset FOREIGN KEY (asset_id) REFERENCES asset(id)
);

CREATE TABLE creator_wallpaper_publication (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    environment_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_json JSON NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
    stage VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'QUEUED',
    wallpaper_id BIGINT NULL,
    wallpaper_version BIGINT NULL,
    steps JSON NOT NULL,
    result JSON NULL,
    error_code VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_message VARCHAR(512) NULL,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    attempt INT NOT NULL DEFAULT 0,
    created_by_admin_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_creator_publication_key UNIQUE (environment_id, idempotency_key),
    CONSTRAINT fk_creator_publication_wallpaper FOREIGN KEY (wallpaper_id) REFERENCES wallpaper(id),
    CONSTRAINT fk_creator_publication_admin FOREIGN KEY (created_by_admin_id) REFERENCES admin_account(id),
    CONSTRAINT ck_creator_publication_state CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','NEEDS_INPUT')),
    INDEX ix_creator_publication_queue (environment_id, state, id),
    INDEX ix_creator_publication_wallpaper (wallpaper_id, id)
);

CREATE TABLE creator_wallpaper_link (
    environment_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_project_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    wallpaper_id BIGINT NOT NULL,
    PRIMARY KEY (environment_id, client_project_key),
    CONSTRAINT uk_creator_link_wallpaper UNIQUE (environment_id, wallpaper_id),
    CONSTRAINT fk_creator_link_wallpaper FOREIGN KEY (wallpaper_id) REFERENCES wallpaper(id)
);
