CREATE TABLE point_topics (
    id uuid PRIMARY KEY,
    point_id uuid NOT NULL REFERENCES points(id) ON DELETE CASCADE,
    preset_key text NOT NULL REFERENCES topic_presets(key),
    custom_label text,
    created_at timestamptz NOT NULL,
    CONSTRAINT point_topics_custom_label_check CHECK (
        (preset_key = 'other' AND custom_label IS NOT NULL
            AND btrim(custom_label) <> '' AND char_length(btrim(custom_label)) <= 40)
        OR (preset_key <> 'other' AND custom_label IS NULL)
    )
);
CREATE UNIQUE INDEX point_topics_normalized_unique
    ON point_topics (point_id, preset_key, COALESCE(lower(btrim(custom_label)), ''));
CREATE INDEX point_topics_point_id_idx ON point_topics (point_id);

CREATE TABLE chats (
    id uuid PRIMARY KEY,
    point_topic_id uuid NOT NULL UNIQUE REFERENCES point_topics(id) ON DELETE CASCADE,
    last_message_seq bigint NOT NULL DEFAULT 0 CHECK (last_message_seq >= 0),
    created_at timestamptz NOT NULL
);

CREATE TABLE chat_memberships (
    chat_id uuid NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at timestamptz NOT NULL,
    left_at timestamptz,
    last_read_seq bigint NOT NULL DEFAULT 0 CHECK (last_read_seq >= 0),
    PRIMARY KEY (chat_id, user_id),
    CONSTRAINT chat_memberships_left_after_join_check CHECK (left_at IS NULL OR left_at >= joined_at)
);
CREATE INDEX chat_memberships_user_left_idx ON chat_memberships (user_id, left_at);
CREATE INDEX chat_memberships_chat_left_idx ON chat_memberships (chat_id, left_at);
