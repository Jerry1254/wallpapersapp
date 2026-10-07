-- Preserve the submitted JSON tree for retries after workspace persistence.
-- Existing hashes and jobs remain intact; legacy batches are upgraded on a matching retry.
ALTER TABLE creator_publish_batch ADD COLUMN request_snapshot JSON NULL;
