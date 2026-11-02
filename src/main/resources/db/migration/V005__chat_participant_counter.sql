-- Block writers and chat deletion for the entire backfill/trigger installation.
-- Lock chats first, matching the parent-before-membership order of mutations.
LOCK TABLE chats IN SHARE ROW EXCLUSIVE MODE;
LOCK TABLE chat_memberships IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE chats
    ADD COLUMN participant_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT chats_participant_count_check CHECK (participant_count >= 0);

UPDATE chats AS c
SET participant_count = (
    SELECT count(*)::integer
    FROM chat_memberships AS m
    WHERE m.chat_id = c.id AND m.left_at IS NULL
);

CREATE FUNCTION maintain_chat_participant_count() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    delta integer := 0;
    target_chat_id uuid;
BEGIN
    IF TG_OP = 'INSERT' THEN
        target_chat_id := NEW.chat_id;
        IF NEW.left_at IS NULL THEN delta := 1; END IF;
    ELSIF TG_OP = 'DELETE' THEN
        target_chat_id := OLD.chat_id;
        IF OLD.left_at IS NULL THEN delta := -1; END IF;
    ELSE
        IF NEW.chat_id IS DISTINCT FROM OLD.chat_id OR NEW.user_id IS DISTINCT FROM OLD.user_id THEN
            RAISE EXCEPTION 'Membership keys cannot be changed';
        END IF;
        target_chat_id := NEW.chat_id;
        IF OLD.left_at IS NULL AND NEW.left_at IS NOT NULL THEN delta := -1; END IF;
        IF OLD.left_at IS NOT NULL AND NEW.left_at IS NULL THEN delta := 1; END IF;
    END IF;

    IF delta <> 0 THEN
        -- A cascading chat deletion no longer has a parent row to update.
        UPDATE chats SET participant_count = participant_count + delta
        WHERE id = target_chat_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER chat_memberships_participant_count
AFTER INSERT OR UPDATE OR DELETE ON chat_memberships
FOR EACH ROW EXECUTE FUNCTION maintain_chat_participant_count();

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM chats AS c
        WHERE c.participant_count <> (
            SELECT count(*) FROM chat_memberships AS m
            WHERE m.chat_id = c.id AND m.left_at IS NULL
        )
    ) THEN
        RAISE EXCEPTION 'Chat participant count backfill mismatch';
    END IF;
END;
$$;
