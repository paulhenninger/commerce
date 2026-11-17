-- The trace of the event that queued an email, so its sending joins that trace.
ALTER TABLE notifications ADD COLUMN traceparent VARCHAR(64) NULL;
-- What the "emails waiting too long" alert measures.
ALTER TABLE notifications ADD KEY idx_notifications_pending (status, created_at);
