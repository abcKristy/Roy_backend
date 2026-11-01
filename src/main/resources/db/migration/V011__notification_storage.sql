CREATE TABLE device_registrations (
    installation_id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform text NOT NULL CHECK (platform IN ('ANDROID_RUSTORE', 'IOS_APNS')),
    token_ciphertext text NOT NULL CHECK (btrim(token_ciphertext) <> ''),
    token_digest text NOT NULL CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE UNIQUE INDEX device_registrations_active_token_unique
    ON device_registrations (platform, token_digest) WHERE revoked_at IS NULL;
CREATE INDEX device_registrations_user_idx ON device_registrations (user_id);

CREATE TABLE notification_locations (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    position geography(Point, 4326) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT notification_locations_bounds_check CHECK (
        NOT ST_IsEmpty(position::geometry)
        AND ST_X(position::geometry) BETWEEN -180 AND 180
        AND ST_Y(position::geometry) BETWEEN -90 AND 90
    )
);
CREATE INDEX notification_locations_position_gist_idx
    ON notification_locations USING gist (position);

CREATE TABLE notification_events (
    id uuid PRIMARY KEY,
    recipient_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    channel text NOT NULL CHECK (channel IN ('REMINDERS', 'PERSONAL', 'EXTRA')),
    event_type text NOT NULL CHECK (event_type IN (
        'reminder_1h', 'chat_message', 'proposal_vote', 'proposal_ready',
        'booking_created', 'booking_cancelled', 'nearby_meeting'
    )),
    dedupe_key text NOT NULL CHECK (btrim(dedupe_key) <> ''),
    point_id uuid,
    chat_id uuid,
    proposal_id uuid,
    -- Private event reference; public notification targets still expose only authorized resources.
    booking_id uuid,
    display_snapshot jsonb NOT NULL,
    visible_in_app boolean NOT NULL,
    push_requested boolean NOT NULL,
    created_at timestamptz NOT NULL,
    read_at timestamptz,
    CONSTRAINT notification_events_recipient_dedupe_unique
        UNIQUE (recipient_user_id, dedupe_key),
    CONSTRAINT notification_events_snapshot_check CHECK (
        jsonb_typeof(display_snapshot) = 'object'
        AND octet_length(display_snapshot::text) <= 16384
    ),
    CONSTRAINT notification_events_channel_event_type_check CHECK (
        (event_type = 'reminder_1h' AND channel = 'REMINDERS')
        OR (event_type = 'nearby_meeting' AND channel = 'EXTRA')
        OR (event_type IN ('chat_message', 'proposal_vote', 'proposal_ready',
            'booking_created', 'booking_cancelled') AND channel = 'PERSONAL')
    ),
    CONSTRAINT notification_events_visibility_check CHECK (
        channel = 'EXTRA' OR visible_in_app
    ),
    CONSTRAINT notification_events_booking_ref_check CHECK (
        (event_type IN ('reminder_1h', 'booking_created', 'booking_cancelled'))
            = (booking_id IS NOT NULL)
    )
);
CREATE INDEX notification_events_recipient_created_idx
    ON notification_events (recipient_user_id, created_at DESC, id DESC);
