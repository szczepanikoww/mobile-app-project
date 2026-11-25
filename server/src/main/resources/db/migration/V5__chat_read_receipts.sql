-- Chat read receipts ("seen at ...").
--
-- A read marker already says up to which message a user has read. read_at adds when the marker last moved forward,
-- so the other party can see "Seen 14:32". It is the time of the latest read, not one timestamp per message: every
-- message up to last_read_seq counts as read, and the shown time is the moment the reader caught up.
ALTER TABLE messaging.read_marker
    ADD COLUMN read_at timestamptz NOT NULL DEFAULT now();
