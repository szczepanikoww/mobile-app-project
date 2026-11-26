-- Trainer-side archive of a client ("Archiwum" in the trainer's client list).
--
-- Archiving only moves the client out of the trainer's current list; the relationship, its grants, sessions and plans
-- stay as they are, so restoring is just clearing the column. Ending a relationship stays a separate command.
ALTER TABLE identity.trainer_client
    ADD COLUMN archived_at timestamptz;

-- The trainer's private comment on one session ("Komentarz" in the session details). Not shown to the client.
ALTER TABLE scheduling.session
    ADD COLUMN trainer_comment text;
