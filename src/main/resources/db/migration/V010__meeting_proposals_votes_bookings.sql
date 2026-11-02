CREATE TABLE meeting_proposals (
    id uuid PRIMARY KEY,
    chat_id uuid NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    proposer_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    lifecycle text NOT NULL DEFAULT 'OPEN',
    closed_reason text,
    revision bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT meeting_proposals_interval_check CHECK (ends_at > starts_at),
    CONSTRAINT meeting_proposals_revision_check CHECK (revision >= 0),
    CONSTRAINT meeting_proposals_lifecycle_check CHECK (
        (lifecycle = 'OPEN' AND closed_reason IS NULL)
        OR (lifecycle = 'CLOSED' AND closed_reason IS NOT NULL
            AND closed_reason IN ('AUTHOR', 'TIME', 'AUTHOR_DELETED'))
    )
);

-- A proposal's time window is immutable; cancellation closes it instead.
CREATE FUNCTION reject_meeting_proposal_interval_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.starts_at IS DISTINCT FROM OLD.starts_at
        OR NEW.ends_at IS DISTINCT FROM OLD.ends_at THEN
        RAISE EXCEPTION 'Meeting proposal interval cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER meeting_proposals_interval_immutable
BEFORE UPDATE ON meeting_proposals
FOR EACH ROW EXECUTE FUNCTION reject_meeting_proposal_interval_update();

CREATE INDEX meeting_proposals_chat_starts_idx
    ON meeting_proposals (chat_id, starts_at DESC);
CREATE INDEX meeting_proposals_chat_created_idx
    ON meeting_proposals (chat_id, created_at DESC, id DESC);
CREATE INDEX meeting_proposals_interval_idx
    ON meeting_proposals (starts_at, ends_at);
CREATE INDEX meeting_proposals_proposer_idx
    ON meeting_proposals (proposer_user_id) WHERE proposer_user_id IS NOT NULL;

CREATE TABLE votes (
    proposal_id uuid NOT NULL REFERENCES meeting_proposals(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (proposal_id, user_id)
);
CREATE INDEX votes_user_proposal_idx ON votes (user_id, proposal_id);
CREATE INDEX votes_proposal_created_idx
    ON votes (proposal_id, created_at DESC, user_id DESC);

CREATE TABLE bookings (
    id uuid PRIMARY KEY,
    booking_order bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    proposal_id uuid NOT NULL REFERENCES meeting_proposals(id) ON DELETE CASCADE,
    booked_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    status text NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL,
    cancelled_at timestamptz,
    cancel_reason text,
    CONSTRAINT bookings_state_check CHECK (
        (status = 'ACTIVE' AND cancelled_at IS NULL AND cancel_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL
            AND cancel_reason IS NOT NULL
            AND cancel_reason IN ('INSUFFICIENT_VOTES', 'CLOSED_BY_AUTHOR', 'POINT_DELETED'))
    )
);
CREATE UNIQUE INDEX bookings_one_active_per_proposal_idx
    ON bookings (proposal_id) WHERE status = 'ACTIVE';
CREATE INDEX bookings_proposal_idx ON bookings (proposal_id);
CREATE INDEX bookings_booked_by_idx ON bookings (booked_by_user_id)
    WHERE booked_by_user_id IS NOT NULL;
