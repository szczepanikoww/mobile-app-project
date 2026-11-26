-- Plans for one session (docs/plans/trainer-redesign.md): a plan pinned to a session is stored with status SESSION, so
-- it does not replace the client's current (ACTIVE) plan. The client trains it on the day of that session; on every
-- other day the ACTIVE plan applies. At most one SESSION plan per session.
ALTER TABLE training.assigned_plan DROP CONSTRAINT assigned_plan_status_check;
ALTER TABLE training.assigned_plan
    ADD CONSTRAINT assigned_plan_status_check CHECK (status IN ('ACTIVE', 'REPLACED', 'ENDED', 'SESSION'));
ALTER TABLE training.assigned_plan
    ADD CONSTRAINT assigned_plan_session_needs_session CHECK (status <> 'SESSION' OR session_id IS NOT NULL);
CREATE UNIQUE INDEX assigned_plan_one_per_session ON training.assigned_plan (session_id) WHERE status = 'SESSION';
