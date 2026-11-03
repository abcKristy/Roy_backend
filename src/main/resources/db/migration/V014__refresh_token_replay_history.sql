-- Keep every consumed hash for the lifetime of its session, including the fixed expiry.
CREATE TABLE auth_session_used_refresh_tokens (
    session_id uuid NOT NULL REFERENCES auth_sessions(id) ON DELETE CASCADE,
    token_hash text NOT NULL,
    PRIMARY KEY (session_id, token_hash)
);

-- Existing installations retain only the immediately previous hash. Preserve that
-- evidence; hashes discarded before this migration cannot be reconstructed.
INSERT INTO auth_session_used_refresh_tokens (session_id, token_hash)
SELECT id, previous_refresh_token_hash
FROM auth_sessions
WHERE previous_refresh_token_hash IS NOT NULL;

-- A rotated legacy session may have discarded older hashes that cannot be
-- reconstructed. Fail closed for those sessions; never-rotated sessions retain
-- their current credentials and will build complete history from now on.
UPDATE auth_sessions
SET revoked_at = clock_timestamp()
WHERE previous_refresh_token_hash IS NOT NULL
  AND revoked_at IS NULL;
