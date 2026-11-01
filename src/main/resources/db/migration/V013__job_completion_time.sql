-- Terminal retention is measured from completion, not from the next scheduled attempt.
ALTER TABLE push_delivery_jobs ADD COLUMN completed_at timestamptz;
ALTER TABLE media_deletion_jobs ADD COLUMN completed_at timestamptz;

-- Older terminal rows have no reliable transition timestamp except SENT.sent_at.
-- Start a fresh 30-day retention window for their other terminal states.
UPDATE push_delivery_jobs SET completed_at = CASE
    WHEN status = 'SENT' THEN sent_at ELSE clock_timestamp() END
WHERE status IN ('SENT', 'FAILED', 'CANCELLED');
UPDATE media_deletion_jobs SET completed_at = clock_timestamp()
WHERE status IN ('DONE', 'FAILED', 'CANCELLED');

ALTER TABLE push_delivery_jobs ADD CONSTRAINT push_jobs_completed_at_check CHECK (
    (status IN ('SENT', 'FAILED', 'CANCELLED')) = (completed_at IS NOT NULL)
);
ALTER TABLE media_deletion_jobs ADD CONSTRAINT media_jobs_completed_at_check CHECK (
    (status IN ('DONE', 'FAILED', 'CANCELLED')) = (completed_at IS NOT NULL)
);

CREATE INDEX push_jobs_terminal_cleanup_idx ON push_delivery_jobs (completed_at, id)
WHERE status IN ('SENT', 'FAILED', 'CANCELLED');
CREATE INDEX media_jobs_terminal_cleanup_idx ON media_deletion_jobs (completed_at, id)
WHERE status IN ('DONE', 'FAILED', 'CANCELLED');
