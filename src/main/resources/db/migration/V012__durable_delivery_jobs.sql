CREATE TABLE push_delivery_jobs (
    id uuid PRIMARY KEY,
    notification_event_id uuid NOT NULL REFERENCES notification_events(id) ON DELETE CASCADE,
    installation_id uuid NOT NULL REFERENCES device_registrations(installation_id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    claim_token uuid,
    lease_until timestamptz,
    last_error text,
    sent_at timestamptz,
    CONSTRAINT push_delivery_jobs_event_installation_unique
        UNIQUE (notification_event_id, installation_id),
    CONSTRAINT push_delivery_jobs_state_check CHECK (
        status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED')
        AND ((status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
            OR (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL))
        AND ((status = 'SENT' AND sent_at IS NOT NULL)
            OR (status <> 'SENT' AND sent_at IS NULL))
    )
);
CREATE INDEX push_delivery_jobs_installation_idx
    ON push_delivery_jobs (installation_id);
CREATE INDEX push_delivery_jobs_due_idx
    ON push_delivery_jobs (status, next_attempt_at, id);
CREATE INDEX push_delivery_jobs_expired_lease_idx
    ON push_delivery_jobs (lease_until, id) WHERE status = 'PROCESSING';

-- No user FK: deletion must finish after the account has been anonymized.
CREATE TABLE media_deletion_jobs (
    id uuid PRIMARY KEY,
    object_key text NOT NULL CHECK (btrim(object_key) <> ''),
    status text NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL,
    claim_token uuid,
    lease_until timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT media_deletion_jobs_state_check CHECK (
        status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED', 'CANCELLED')
        AND ((status = 'PROCESSING' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
            OR (status <> 'PROCESSING' AND claim_token IS NULL AND lease_until IS NULL))
    )
);
CREATE INDEX media_deletion_jobs_due_idx
    ON media_deletion_jobs (status, next_attempt_at, id);
CREATE INDEX media_deletion_jobs_expired_lease_idx
    ON media_deletion_jobs (lease_until, id) WHERE status = 'PROCESSING';
