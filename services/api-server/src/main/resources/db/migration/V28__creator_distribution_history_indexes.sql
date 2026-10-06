-- Complete task history and dated metric snapshots; no records are removed.
ALTER TABLE creator_publish_job
    ADD INDEX idx_creator_job_created (created_at, id),
    ADD INDEX idx_creator_job_account_created (account_id, created_at, id);

ALTER TABLE creator_account_metrics
    ADD INDEX idx_creator_metrics_time (account_id, collected_at, id);
