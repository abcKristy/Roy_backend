-- M2.T6.B01 synthetic acceptance fixture. Apply once to a disposable
-- roy_m2t6_fixture database cloned from migrated V013 + m1-t4-viewport-fixture.
-- Expected values below are fixed by the contract and WGS84 coordinates.
BEGIN;
DO $$ BEGIN
    IF current_database() <> 'roy_m2t6_fixture' THEN
        RAISE EXCEPTION 'M2 fixture requires roy_m2t6_fixture';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM points WHERE id = '20000000-0000-4000-8000-000000000002')
       OR EXISTS (SELECT 1 FROM users WHERE id = '50000000-0000-4000-8000-000000000001') THEN
        RAISE EXCEPTION 'M2 fixture requires the clean migrated viewport clone';
    END IF;
END $$;

INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
VALUES
 ('50000000-0000-4000-8000-000000000001','+12025550991','synthetic-unusable-hash','M2 anchor',20,
  'fixture-only','2026-11-03T12:00:00Z','2026-11-03T12:00:00Z','2026-11-03T12:00:00Z'),
 ('50000000-0000-4000-8000-000000000002','+12025550992','synthetic-unusable-hash','M2 inside',20,
  'fixture-only','2026-11-03T12:00:00Z','2026-11-03T12:00:00Z','2026-11-03T12:00:00Z'),
 ('50000000-0000-4000-8000-000000000003','+12025550993','synthetic-unusable-hash','M2 outside',20,
  'fixture-only','2026-11-03T12:00:00Z','2026-11-03T12:00:00Z','2026-11-03T12:00:00Z');

-- WGS84 equatorial arc is about 111319.49 m/degree: 0.00089° ≈ 99.08 m,
-- 0.00091° ≈ 101.30 m. Geometry is written lon,lat; geography uses metres.
INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
VALUES
 ('60000000-0000-4000-8000-000000000001',ST_SetSRID(ST_MakePoint(0,0),4326),'M2 anchor','MANUAL',
  '50000000-0000-4000-8000-000000000001','2026-11-03T12:00:00Z','2026-11-03T12:00:00Z'),
 ('60000000-0000-4000-8000-000000000002',ST_SetSRID(ST_MakePoint(0.00089,0),4326),'M2 inside 100m','MANUAL',
  '50000000-0000-4000-8000-000000000002','2026-11-03T12:00:01Z','2026-11-03T12:00:01Z'),
 ('60000000-0000-4000-8000-000000000003',ST_SetSRID(ST_MakePoint(0.00091,0),4326),'M2 outside 100m','MANUAL',
  '50000000-0000-4000-8000-000000000003','2026-11-03T12:00:02Z','2026-11-03T12:00:02Z');

INSERT INTO point_topics (id,point_id,preset_key,created_at)
SELECT ('70000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
       ('60000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
       'football','2026-11-03T12:00:00Z'::timestamptz
FROM generate_series(1,3) AS n;
INSERT INTO chats (id,point_topic_id,created_at)
SELECT ('80000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
       ('70000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
       '2026-11-03T12:00:00Z'::timestamptz
FROM generate_series(1,3) AS n;
INSERT INTO chat_memberships (chat_id,user_id,joined_at)
SELECT ('80000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
       ('50000000-0000-4000-8000-' || lpad(n::text,12,'0'))::uuid,
       '2026-11-03T12:00:00Z'::timestamptz
FROM generate_series(1,3) AS n;

-- At fixed 2026-11-03 12:00:00Z: week includes exactly -7d and -7d+1s;
-- month includes exactly -30d, -7d-1s, -7d, and -7d+1s.
-- The event at now and the event at -30d-1s are excluded from both windows.
INSERT INTO point_creation_events (id,creator_user_id,point_id,created_at)
VALUES
 ('90000000-0000-4000-8000-000000000001','50000000-0000-4000-8000-000000000001',NULL,'2026-10-04T11:59:59Z'),
 ('90000000-0000-4000-8000-000000000002','50000000-0000-4000-8000-000000000001',NULL,'2026-10-04T12:00:00Z'),
 ('90000000-0000-4000-8000-000000000003','50000000-0000-4000-8000-000000000001',NULL,'2026-10-27T11:59:59Z'),
 ('90000000-0000-4000-8000-000000000004','50000000-0000-4000-8000-000000000001',NULL,'2026-10-27T12:00:00Z'),
 ('90000000-0000-4000-8000-000000000005','50000000-0000-4000-8000-000000000001',NULL,'2026-10-27T12:00:01Z'),
 ('90000000-0000-4000-8000-000000000006','50000000-0000-4000-8000-000000000001',NULL,'2026-11-03T12:00:00Z');

-- Reject a broken fixture before it can be used as an oracle.
DO $$ BEGIN
    IF (SELECT count(*) FROM users WHERE id::text LIKE '50000000-%') <> 3
       OR (SELECT count(*) FROM points WHERE id::text LIKE '60000000-%') <> 3
       OR (SELECT count(*) FROM chats WHERE id::text LIKE '80000000-%' AND participant_count=1) <> 3
       OR (SELECT count(*) FROM point_creation_events WHERE creator_user_id='50000000-0000-4000-8000-000000000001') <> 6 THEN
        RAISE EXCEPTION 'M2 fixture cardinality mismatch';
    END IF;
END $$;
COMMIT;
