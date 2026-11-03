-- A 4096-codepoint message can exceed 16 KiB once its UTF-8 text and DTO
-- envelope are stored. Keep the existing bound for every other operation.
ALTER TABLE operation_idempotency
    DROP CONSTRAINT operation_idempotency_snapshot_check;
ALTER TABLE operation_idempotency
    ADD CONSTRAINT operation_idempotency_snapshot_check CHECK (
        response_snapshot IS NULL OR (
            jsonb_typeof(response_snapshot) = 'object'
            AND octet_length(response_snapshot::text) <= CASE
                WHEN operation_type = 'send_message' THEN 65536
                ELSE 16384
            END
        )
    );
