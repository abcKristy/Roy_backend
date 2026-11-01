CREATE TABLE points (
    id uuid PRIMARY KEY,
    location geometry(Point, 4326) NOT NULL,
    address text NOT NULL CHECK (btrim(address) <> ''),
    address_source text NOT NULL CHECK (address_source IN ('GEOCODED', 'MANUAL')),
    created_by_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT points_location_bounds_check CHECK (
        NOT ST_IsEmpty(location)
        AND ST_X(location) BETWEEN -180 AND 180
        AND ST_Y(location) BETWEEN -90 AND 90
    )
);
CREATE INDEX points_location_gist_idx ON points USING gist (location);
CREATE INDEX points_location_geography_gist_idx ON points USING gist ((location::geography));

CREATE FUNCTION reject_point_location_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.location IS DISTINCT FROM OLD.location THEN
        RAISE EXCEPTION 'Point location cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER points_location_immutable
BEFORE UPDATE ON points
FOR EACH ROW EXECUTE FUNCTION reject_point_location_update();

CREATE TABLE point_creation_events (
    id uuid PRIMARY KEY,
    creator_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    point_id uuid REFERENCES points(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX point_creation_events_creator_time_idx
    ON point_creation_events (creator_user_id, created_at DESC);

CREATE TABLE operation_idempotency (
    actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation_type text NOT NULL CHECK (btrim(operation_type) <> ''),
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key) <> ''),
    request_hash text NOT NULL CHECK (btrim(request_hash) <> ''),
    outcome text NOT NULL CHECK (outcome IN ('SUCCESS', 'QUOTA_EXCEEDED')),
    result_ref uuid,
    response_snapshot jsonb,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (actor_user_id, operation_type, idempotency_key)
);
CREATE INDEX operation_idempotency_created_at_idx ON operation_idempotency (created_at);
