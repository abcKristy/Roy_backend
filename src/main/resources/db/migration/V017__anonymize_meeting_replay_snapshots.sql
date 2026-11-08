-- Replay rows can outlive meetings, so match deleted users directly in the saved JSON.
UPDATE operation_idempotency AS operation
SET response_snapshot = jsonb_set(response_snapshot, '{body,proposedByUserId}', 'null'::jsonb, false)
FROM users AS author
WHERE operation.operation_type IN ('book_meeting', 'close_meeting')
  AND author.deleted_at IS NOT NULL
  AND operation.response_snapshot #>> '{body,proposedByUserId}' = author.id::text;

UPDATE operation_idempotency AS operation
SET response_snapshot = jsonb_set(response_snapshot, '{body,booking,bookedByUserId}', 'null'::jsonb, false)
FROM users AS booker
WHERE operation.operation_type IN ('book_meeting', 'close_meeting')
  AND booker.deleted_at IS NOT NULL
  AND operation.response_snapshot #>> '{body,booking,bookedByUserId}' = booker.id::text;
