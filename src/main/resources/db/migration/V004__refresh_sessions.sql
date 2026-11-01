-- Existing sessions keep their original access JWT until expiry, but cannot refresh.
ALTER TABLE auth_sessions
    ADD COLUMN auth_version integer,
    ADD COLUMN refresh_token_hash text,
    ADD COLUMN previous_refresh_token_hash text,
    ADD CONSTRAINT auth_sessions_refresh_state_check CHECK (
        (auth_version IS NULL AND refresh_token_hash IS NULL AND previous_refresh_token_hash IS NULL)
        OR (auth_version IS NOT NULL AND auth_version >= 0
            AND refresh_token_hash IS NOT NULL
            AND refresh_token_hash ~ '^[0-9a-f]{64}$'
            AND (previous_refresh_token_hash IS NULL OR previous_refresh_token_hash ~ '^[0-9a-f]{64}$'))
    );

CREATE UNIQUE INDEX auth_sessions_refresh_token_hash_unique
    ON auth_sessions (refresh_token_hash) WHERE refresh_token_hash IS NOT NULL;
