-- M1.T4.B01 synthetic viewport fixture. Apply only to an empty, disposable
-- roy_m1t4_viewport database migrated through V005 or later.
-- Fixed IDs make the expected projection reproducible. No production seed uses this file.
BEGIN;
DO $$ BEGIN
    IF current_database() <> 'roy_m1t4_viewport' THEN
        RAISE EXCEPTION 'Viewport fixture requires roy_m1t4_viewport';
    END IF;
    IF EXISTS (SELECT 1 FROM users) OR EXISTS (SELECT 1 FROM points)
        OR EXISTS (SELECT 1 FROM point_topics) OR EXISTS (SELECT 1 FROM chats)
        OR EXISTS (SELECT 1 FROM chat_memberships) THEN
        RAISE EXCEPTION 'Viewport fixture requires empty user and point tables';
    END IF;
END $$;

INSERT INTO users (id, phone_e164, password_hash, display_name, age_years,
                   accepted_privacy_version, accepted_privacy_at, created_at, updated_at)
SELECT ('10000000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
       '+1202555010' || n, 'synthetic-unusable-hash', 'Viewport user ' || n,
       20, 'fixture-only', '2026-11-02T12:00:00Z',
       '2026-11-02T12:00:00Z', '2026-11-02T12:00:00Z'
FROM generate_series(1, 4) AS n;

INSERT INTO points (id, location, address, address_source, created_by_user_id, created_at, updated_at)
VALUES
    ('20000000-0000-4000-8000-000000000001', ST_SetSRID(ST_MakePoint(37.6200, 55.7500), 4326),
     'Synthetic single-topic point', 'MANUAL', '10000000-0000-4000-8000-000000000001',
     '2026-11-02T12:00:00Z', '2026-11-02T12:00:00Z'),
    ('20000000-0000-4000-8000-000000000002', ST_SetSRID(ST_MakePoint(37.6250, 55.7550), 4326),
     'Synthetic multi-topic point', 'MANUAL', '10000000-0000-4000-8000-000000000002',
     '2026-11-02T12:01:00Z', '2026-11-02T12:01:00Z');

INSERT INTO point_topics (id, point_id, preset_key, custom_label, created_at)
VALUES
    ('30000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001',
     'football', NULL, '2026-11-02T12:00:00Z'),
    ('30000000-0000-4000-8000-000000000002', '20000000-0000-4000-8000-000000000002',
     'football', NULL, '2026-11-02T12:01:00Z'),
    ('30000000-0000-4000-8000-000000000003', '20000000-0000-4000-8000-000000000002',
     'other', 'Шахматы', '2026-11-02T12:01:00Z');

INSERT INTO chats (id, point_topic_id, created_at)
VALUES
    ('40000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', '2026-11-02T12:00:00Z'),
    ('40000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000002', '2026-11-02T12:01:00Z'),
    ('40000000-0000-4000-8000-000000000003', '30000000-0000-4000-8000-000000000003', '2026-11-02T12:01:00Z');

-- The V005 membership trigger computes each chat's participant_count.
-- User 2 belongs to both multi-topic chats, so point peopleCount is 3, not 4.
INSERT INTO chat_memberships (chat_id, user_id, joined_at, left_at)
VALUES
    ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '2026-11-02T12:02:00Z', NULL),
    ('40000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '2026-11-02T12:02:00Z', NULL),
    ('40000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000002', '2026-11-02T12:02:00Z', NULL),
    ('40000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000002', '2026-11-02T12:02:00Z', NULL),
    ('40000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000003', '2026-11-02T12:02:00Z', NULL),
    ('40000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000004', '2026-11-02T12:02:00Z', '2026-11-02T12:03:00Z');

-- Verify stored chat counters and the distinct active people projected per point.
DO $$ BEGIN
    IF EXISTS (
        SELECT 1 FROM chats c WHERE c.participant_count <>
            (SELECT count(*) FROM chat_memberships m WHERE m.chat_id = c.id AND m.left_at IS NULL)
    ) THEN
        RAISE EXCEPTION 'Viewport fixture chat counters do not match active memberships';
    END IF;
    IF (SELECT count(DISTINCT m.user_id) FROM point_topics pt
        JOIN chats c ON c.point_topic_id = pt.id
        JOIN chat_memberships m ON m.chat_id = c.id AND m.left_at IS NULL
        WHERE pt.point_id = '20000000-0000-4000-8000-000000000001') <> 1
       OR (SELECT count(DISTINCT m.user_id) FROM point_topics pt
        JOIN chats c ON c.point_topic_id = pt.id
        JOIN chat_memberships m ON m.chat_id = c.id AND m.left_at IS NULL
        WHERE pt.point_id = '20000000-0000-4000-8000-000000000002') <> 3 THEN
        RAISE EXCEPTION 'Viewport fixture point counts differ from active memberships';
    END IF;
END $$;
COMMIT;
