CREATE TABLE creator_social_account (
    id CHAR(36) PRIMARY KEY,
    platform VARCHAR(16) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    group_name VARCHAR(80) NOT NULL DEFAULT '',
    runner_id CHAR(36) NOT NULL,
    login_status VARCHAR(24) NOT NULL DEFAULT 'disconnected',
    checked_at TIMESTAMP(3) NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE creator_publish_media (
    id CHAR(36) PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    media_type VARCHAR(16) NOT NULL,
    extension VARCHAR(8) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE creator_publish_batch (
    id CHAR(36) PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE creator_publish_job (
    id CHAR(36) PRIMARY KEY,
    batch_id CHAR(36) NOT NULL,
    account_id CHAR(36) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'queued',
    message VARCHAR(1000) NOT NULL DEFAULT '',
    due_at TIMESTAMP(3) NOT NULL,
    scheduled BOOLEAN NOT NULL DEFAULT FALSE,
    lease_token CHAR(36) NULL,
    heartbeat_at TIMESTAMP(3) NULL,
    result_url VARCHAR(1000) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_creator_job_batch FOREIGN KEY (batch_id) REFERENCES creator_publish_batch(id),
    CONSTRAINT fk_creator_job_account FOREIGN KEY (account_id) REFERENCES creator_social_account(id),
    UNIQUE KEY uk_creator_batch_account (batch_id, account_id),
    INDEX idx_creator_job_queue (status, due_at),
    INDEX idx_creator_job_account (account_id, status)
);

CREATE TABLE creator_account_metrics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id CHAR(36) NOT NULL,
    metrics JSON NOT NULL,
    source_url VARCHAR(1000) NOT NULL,
    collected_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_creator_metrics_account FOREIGN KEY (account_id) REFERENCES creator_social_account(id),
    INDEX idx_creator_metrics_account (account_id, id)
);
