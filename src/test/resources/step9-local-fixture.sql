-- Synthetic local Step 9 fixture. Run only on a disposable, empty V013 database.
BEGIN;
DO $$ BEGIN
    IF current_database() !~ '^roy_m1t2_step9load$' THEN
        RAISE EXCEPTION 'Step 9 fixture requires roy_m1t2_step9load';
    END IF;
    IF EXISTS (SELECT 1 FROM users) OR EXISTS (SELECT 1 FROM points) THEN
        RAISE EXCEPTION 'Step 9 fixture requires empty user and point tables';
    END IF;
END $$;

CREATE TEMP TABLE fixture_users ON COMMIT DROP AS
SELECT n, gen_random_uuid() AS id FROM generate_series(1, 100) AS n;
INSERT INTO users (id,phone_e164,password_hash,display_name,age_years,
                   accepted_privacy_version,accepted_privacy_at,created_at,updated_at)
SELECT id, '+7900000' || lpad(n::text, 4, '0'), 'synthetic-hash',
       'Synthetic ' || n, 20, 'fixture-v1', clock_timestamp(),
       clock_timestamp(), clock_timestamp()
FROM fixture_users;
INSERT INTO notification_settings (user_id,updated_at)
SELECT id, clock_timestamp() FROM fixture_users;

CREATE TEMP TABLE fixture_points ON COMMIT DROP AS
SELECT n, gen_random_uuid() AS id,
       ((n - 1) % 100) + 1 AS creator_n,
       37.55 + ((n - 1) % 35) * 0.004 AS lon,
       55.70 + ((n - 1) / 35) * 0.004 AS lat
FROM generate_series(1, 350) AS n;
INSERT INTO points (id,location,address,address_source,created_by_user_id,created_at,updated_at)
SELECT p.id, ST_SetSRID(ST_MakePoint(p.lon,p.lat),4326),
       'Synthetic address ' || p.n, 'MANUAL', u.id,
       clock_timestamp() - (p.n * interval '1 second'), clock_timestamp()
FROM fixture_points p JOIN fixture_users u ON u.n=p.creator_n;

CREATE TEMP TABLE fixture_chats ON COMMIT DROP AS
SELECT p.n, p.id AS point_id, gen_random_uuid() AS topic_id,
       gen_random_uuid() AS chat_id, p.creator_n
FROM fixture_points p;
INSERT INTO point_topics (id,point_id,preset_key,created_at)
SELECT topic_id,point_id,'football',clock_timestamp() FROM fixture_chats;
INSERT INTO chats (id,point_topic_id,created_at)
SELECT chat_id,topic_id,clock_timestamp() FROM fixture_chats;
INSERT INTO chat_memberships (chat_id,user_id,joined_at)
SELECT c.chat_id,u.id,clock_timestamp()
FROM fixture_chats c JOIN fixture_users u
  ON u.n IN (c.creator_n, (c.creator_n % 100) + 1);

CREATE TEMP TABLE fixture_messages ON COMMIT DROP AS
SELECT n, ((n - 1) % 350) + 1 AS chat_n,
       row_number() OVER (PARTITION BY ((n - 1) % 350) ORDER BY n) AS message_seq
FROM generate_series(1, 2000) AS n;
INSERT INTO messages (id,chat_id,message_seq,author_user_id,type,body,created_at)
SELECT gen_random_uuid(),c.chat_id,m.message_seq,u.id,'TEXT',
       CASE WHEN m.n % 2=0 THEN 'Редкое слово синтетический маркер ' || m.n
            ELSE 'Частое слово встреча спорт ' || m.n END,
       clock_timestamp() - ((2001-m.n) * interval '1 second')
FROM fixture_messages m JOIN fixture_chats c ON c.n=m.chat_n
JOIN fixture_users u ON u.n=c.creator_n;
UPDATE chats c SET last_message_seq=counts.max_seq
FROM (SELECT chat_id,max(message_seq) AS max_seq FROM messages GROUP BY chat_id) counts
WHERE c.id=counts.chat_id;
ANALYZE users;
ANALYZE points;
ANALYZE point_topics;
ANALYZE chats;
ANALYZE chat_memberships;
ANALYZE messages;
COMMIT;
