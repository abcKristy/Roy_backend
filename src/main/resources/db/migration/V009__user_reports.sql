CREATE TABLE reports (
    id uuid PRIMARY KEY,
    reporter_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    target_user_id uuid NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    context_chat_id uuid REFERENCES chats(id) ON DELETE SET NULL,
    reason_text text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT reports_unique_pair UNIQUE (reporter_user_id, target_user_id),
    CONSTRAINT reports_distinct_users_check CHECK (reporter_user_id <> target_user_id),
    CONSTRAINT reports_reason_check CHECK (
        btrim(reason_text) <> '' AND char_length(reason_text) <= 1000
    )
);

CREATE INDEX reports_target_created_idx ON reports (target_user_id, created_at);
CREATE INDEX reports_context_chat_idx ON reports (context_chat_id)
    WHERE context_chat_id IS NOT NULL;
