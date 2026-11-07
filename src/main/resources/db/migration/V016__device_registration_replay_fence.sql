-- Session timestamps have second resolution. Allocate a stable order at session insertion.
ALTER TABLE auth_sessions ADD COLUMN device_registration_order bigint;

WITH historical_order AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS registration_order
    FROM auth_sessions
)
UPDATE auth_sessions AS session
SET device_registration_order = historical_order.registration_order
FROM historical_order
WHERE historical_order.id = session.id;

ALTER TABLE auth_sessions
    ALTER COLUMN device_registration_order SET NOT NULL;
ALTER TABLE auth_sessions
    ALTER COLUMN device_registration_order ADD GENERATED ALWAYS AS IDENTITY;

SELECT setval(
    pg_get_serial_sequence('auth_sessions', 'device_registration_order'),
    COALESCE(MAX(device_registration_order), 0) + 1,
    false
) FROM auth_sessions;

-- Historical bindings have no session provenance. Their first accepted write sets the fence.
ALTER TABLE device_registrations ADD COLUMN last_session_order bigint;

CREATE TABLE device_retired_tokens (
    installation_id uuid NOT NULL REFERENCES device_registrations(installation_id) ON DELETE CASCADE,
    platform text NOT NULL CHECK (platform IN ('ANDROID_RUSTORE', 'IOS_APNS')),
    token_digest text NOT NULL CHECK (token_digest ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (installation_id, platform, token_digest)
);
