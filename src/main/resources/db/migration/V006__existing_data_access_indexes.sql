-- These are currently small tables. If a target environment has grown large,
-- use a separately numbered non-transactional CONCURRENTLY migration instead.
CREATE INDEX points_created_at_id_idx ON points (created_at DESC, id DESC);
CREATE INDEX points_created_by_user_id_idx ON points (created_by_user_id)
    WHERE created_by_user_id IS NOT NULL;
CREATE INDEX point_creation_events_point_id_idx ON point_creation_events (point_id)
    WHERE point_id IS NOT NULL;
CREATE INDEX auth_sessions_expires_at_idx ON auth_sessions (expires_at);

-- Stop on invalid legacy rows. Never erase or silently rewrite user data.
ALTER TABLE users ADD CONSTRAINT users_display_name_length_check CHECK (
    deleted_at IS NOT NULL OR
    (display_name IS NOT NULL AND char_length(btrim(display_name)) BETWEEN 1 AND 80)
);
ALTER TABLE points ADD CONSTRAINT points_address_length_check CHECK (
    char_length(btrim(address)) BETWEEN 1 AND 512
);
ALTER TABLE operation_idempotency ADD CONSTRAINT operation_idempotency_snapshot_check CHECK (
    response_snapshot IS NULL OR (
        jsonb_typeof(response_snapshot) = 'object'
        AND octet_length(response_snapshot::text) <= 16384
    )
);

INSERT INTO notification_settings (user_id, updated_at)
SELECT u.id, clock_timestamp()
FROM users AS u
WHERE u.deleted_at IS NULL
ON CONFLICT (user_id) DO NOTHING;
