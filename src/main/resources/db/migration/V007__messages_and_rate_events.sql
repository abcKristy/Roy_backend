-- The new message store is empty. Existing nonzero chat/read sequence values
-- are intentionally preserved; no synthetic message history is invented.
CREATE TABLE messages (
    id uuid PRIMARY KEY,
    chat_id uuid NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    message_seq bigint NOT NULL CHECK (message_seq > 0),
    author_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    type text NOT NULL CHECK (type IN ('TEXT', 'SYSTEM')),
    body text,
    system_type text,
    system_payload jsonb,
    created_at timestamptz NOT NULL,
    CONSTRAINT messages_content_check CHECK (
        (type = 'TEXT' AND body IS NOT NULL
            AND char_length(btrim(body)) > 0 AND char_length(body) <= 4096
            AND system_type IS NULL AND system_payload IS NULL)
        OR
        (type = 'SYSTEM' AND body IS NULL AND author_user_id IS NULL
            AND system_type IS NOT NULL
            AND system_type IN (
                'meeting_proposed', 'meeting_ready', 'meeting_booked',
                'meeting_booking_cancelled', 'meeting_closed',
                'member_joined', 'member_left', 'point_deleted'
            )
            AND system_payload IS NOT NULL
            AND jsonb_typeof(system_payload) = 'object'
            AND octet_length(system_payload::text) <= 16384)
    ),
    UNIQUE (chat_id, message_seq)
);
CREATE INDEX messages_chat_created_at_id_idx ON messages (chat_id, created_at DESC, id DESC);
CREATE INDEX messages_author_user_id_idx ON messages (author_user_id)
    WHERE author_user_id IS NOT NULL;

CREATE TABLE message_rate_events (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    accepted_at timestamptz NOT NULL
);
CREATE INDEX message_rate_events_user_time_idx ON message_rate_events (user_id, accepted_at DESC);
CREATE INDEX message_rate_events_accepted_at_idx ON message_rate_events (accepted_at);
