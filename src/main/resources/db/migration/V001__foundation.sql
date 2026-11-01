CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE users (
    id uuid PRIMARY KEY,
    phone_e164 text,
    password_hash text,
    display_name text,
    age_years smallint,
    avatar_object_key text,
    accepted_privacy_version text,
    accepted_privacy_at timestamptz,
    create_point_blocked_until timestamptz,
    message_muted_until timestamptz,
    report_mute_triggered_at timestamptz,
    auth_version integer NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT users_active_profile_check CHECK (
        (deleted_at IS NULL AND phone_e164 IS NOT NULL AND btrim(phone_e164) <> ''
            AND password_hash IS NOT NULL AND btrim(password_hash) <> ''
            AND display_name IS NOT NULL AND btrim(display_name) <> ''
            AND age_years IS NOT NULL AND age_years >= 12
            AND accepted_privacy_version IS NOT NULL AND btrim(accepted_privacy_version) <> ''
            AND accepted_privacy_at IS NOT NULL)
        OR
        (deleted_at IS NOT NULL AND phone_e164 IS NULL AND password_hash IS NULL
            AND display_name IS NULL AND age_years IS NULL AND avatar_object_key IS NULL
            AND accepted_privacy_version IS NULL AND accepted_privacy_at IS NULL
            AND create_point_blocked_until IS NULL AND message_muted_until IS NULL
            AND report_mute_triggered_at IS NULL)
    ),
    CONSTRAINT users_phone_e164_check CHECK (
        phone_e164 IS NULL OR phone_e164 ~ '^[+][1-9][0-9]{1,14}$'
    )
);
CREATE UNIQUE INDEX users_phone_e164_unique ON users (phone_e164) WHERE phone_e164 IS NOT NULL;

CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT auth_sessions_expiry_check CHECK (expires_at > created_at)
);
CREATE INDEX auth_sessions_user_expiry_idx ON auth_sessions (user_id, expires_at);

CREATE TABLE topic_presets (
    key text PRIMARY KEY,
    label_ru text NOT NULL CHECK (btrim(label_ru) <> ''),
    icon_key text NOT NULL CHECK (btrim(icon_key) <> ''),
    sort_order integer NOT NULL UNIQUE
);
INSERT INTO topic_presets (key, label_ru, icon_key, sort_order) VALUES
    ('football', 'Футбол', 'football', 1),
    ('volleyball', 'Волейбол', 'volleyball', 2),
    ('basketball', 'Баскетбол', 'basketball', 3),
    ('tennis', 'Теннис', 'tennis', 4),
    ('running', 'Бег', 'running', 5),
    ('workout', 'Воркаут', 'workout', 6),
    ('table_tennis', 'Настольный теннис', 'table_tennis', 7),
    ('badminton', 'Бадминтон', 'badminton', 8),
    ('board_games', 'Настолки', 'board_games', 9),
    ('mafia', 'Мафия', 'mafia', 10),
    ('other', 'Другое', 'other', 11);

-- Settings are created early because registration in M1.T2 needs an atomic row.
-- M5 adds notification delivery tables in new, append-only migrations.
CREATE TABLE notification_settings (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    reminders_push_enabled boolean NOT NULL DEFAULT true,
    personal_push_enabled boolean NOT NULL DEFAULT true,
    extra_push_enabled boolean NOT NULL DEFAULT false,
    extra_in_app_enabled boolean NOT NULL DEFAULT false,
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at timestamptz NOT NULL
);
