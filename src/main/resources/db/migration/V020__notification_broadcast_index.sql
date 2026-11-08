CREATE INDEX notification_events_broadcast_idx ON notification_events (created_at, id)
    WHERE visible_in_app;
