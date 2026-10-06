-- Preserve every existing job and assign a stable position inside each batch.
-- NULL remains available for older API binaries which do not send a position.
ALTER TABLE creator_publish_job ADD COLUMN batch_position INT NULL;

UPDATE creator_publish_job j
JOIN (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY batch_id ORDER BY created_at, id) - 1 AS position
    FROM creator_publish_job
) ranked ON ranked.id = j.id
SET j.batch_position = ranked.position;

ALTER TABLE creator_publish_job
    ADD UNIQUE KEY uk_creator_batch_position (batch_id, batch_position),
    DROP INDEX uk_creator_batch_account;
