-- A booking snapshot retains every direct overlap. Their count has no domain limit.
ALTER TABLE operation_idempotency
    DROP CONSTRAINT operation_idempotency_snapshot_check;
ALTER TABLE operation_idempotency
    ADD CONSTRAINT operation_idempotency_snapshot_check CHECK (
        response_snapshot IS NULL OR (
            jsonb_typeof(response_snapshot) = 'object'
            AND CASE operation_type
                WHEN 'book_meeting' THEN true
                WHEN 'send_message' THEN octet_length(response_snapshot::text) <= 65536
                ELSE octet_length(response_snapshot::text) <= 16384
            END
        )
    );
