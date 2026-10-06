CREATE TABLE creator_workspace_media (
    id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(24) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
    metadata JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX ix_creator_workspace_media_hash (sha256)
) ENGINE=InnoDB;

CREATE TABLE creator_workspace_record (
    collection_name VARCHAR(24) CHARACTER SET ascii NOT NULL,
    id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload JSON NOT NULL,
    payload_sha256 CHAR(64) CHARACTER SET ascii NOT NULL,
    lock_version BIGINT NOT NULL DEFAULT 1,
    deleted_at DATETIME(3) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (collection_name, id),
    INDEX ix_creator_workspace_record_list (collection_name, deleted_at, updated_at)
) ENGINE=InnoDB;

CREATE TABLE creator_workspace_reference (
    collection_name VARCHAR(24) CHARACTER SET ascii NOT NULL,
    record_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    media_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (collection_name, record_id, media_id),
    FOREIGN KEY (collection_name, record_id) REFERENCES creator_workspace_record(collection_name, id),
    FOREIGN KEY (media_id) REFERENCES creator_workspace_media(id)
) ENGINE=InnoDB;

CREATE TABLE creator_workspace_task (
    id CHAR(36) CHARACTER SET ascii PRIMARY KEY,
    project_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_type VARCHAR(24) CHARACTER SET ascii NOT NULL,
    input_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    input_snapshot JSON NOT NULL,
    state VARCHAR(24) CHARACTER SET ascii NOT NULL DEFAULT 'PREPARING',
    stage VARCHAR(255) NOT NULL DEFAULT '',
    progress DECIMAL(6,2) NULL,
    payload_media_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    output_media_ids JSON NOT NULL,
    runner_id CHAR(36) CHARACTER SET ascii NULL,
    lease_token CHAR(36) CHARACTER SET ascii NULL,
    lease_until DATETIME(3) NULL,
    attempt INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX ix_creator_workspace_task_claim (state, created_at),
    INDEX ix_creator_workspace_task_cache (project_id, task_type, input_hash, state),
    FOREIGN KEY (payload_media_id) REFERENCES creator_workspace_media(id)
) ENGINE=InnoDB;
