CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- The API validates trim(q) length 3..256 and escapes LIKE metacharacters.
CREATE INDEX messages_text_body_trgm_idx ON messages USING gin (body gin_trgm_ops)
    WHERE type = 'TEXT';
