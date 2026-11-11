-- Schema gaps G-1 … G-5 of docs/plans/backend-client-guest.md §10: what the public catalogue needs and V1 lacks.
--
-- Status: PROPOSAL for review with the database owner (open decision R11). Without these columns the guest screens
-- would have to read names from identity.app_user (next to e-mail) or availability from scheduling, which the
-- privacy rule of plan §9.1 forbids. G-6 … G-8 (offer description/highlight, gym amenities/transit, listing media)
-- are NOT added: the API returns null / false / [] for them until the team decides.
--
-- No existing column changes meaning; every addition has a default, so V1 data stays valid.

-- G-1: the public trainer name, written by the Identity module when a trainer publishes or renames, plus the folded
-- search text (contract SearchText.normalize of name, headline, specialties, gyms and districts).
ALTER TABLE catalogue.trainer_listing
    ADD COLUMN display_name text,
    ADD COLUMN search_text  text NOT NULL DEFAULT '';
UPDATE catalogue.trainer_listing l SET display_name = u.display_name
FROM identity.app_user u WHERE u.id = l.trainer_id;
ALTER TABLE catalogue.trainer_listing
    ALTER COLUMN display_name SET NOT NULL,
    ADD CONSTRAINT trainer_listing_display_name_length CHECK (length(display_name) BETWEEN 1 AND 120);
CREATE INDEX trainer_listing_search ON catalogue.trainer_listing USING gin (search_text gin_trgm_ops);

-- G-2: goals a guest can browse by (app Goal enum).
ALTER TABLE catalogue.trainer_listing
    ADD COLUMN goals text[] NOT NULL DEFAULT '{}'
        CHECK (goals <@ ARRAY['REDUCTION', 'STRENGTH', 'MOBILITY', 'COMPETITION']::text[]);

-- G-4: district filter and gym search.
ALTER TABLE catalogue.gym
    ADD COLUMN district    text CHECK (length(district) <= 80),
    ADD COLUMN search_text text NOT NULL DEFAULT '';
CREATE INDEX gym_search ON catalogue.gym USING gin (search_text gin_trgm_ops);

-- G-5: the trainer's main gym.
ALTER TABLE catalogue.trainer_gym ADD COLUMN is_main boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX trainer_gym_one_main ON catalogue.trainer_gym (trainer_id) WHERE is_main;

-- G-3: availability buckets for the guest filter, owned by Scheduling and exposing buckets only (never times,
-- blocks or sessions). Buckets are in the rule's own local time: morning starts before 12:00, afternoon overlaps
-- 12:00–17:00, evening ends after 17:00; Saturday and Sunday rules count as WEEKEND.
CREATE VIEW catalogue.public_trainer_availability AS
SELECT DISTINCT r.trainer_id, b.bucket
FROM scheduling.availability_rule r
CROSS JOIN LATERAL (
    SELECT 'WEEKEND' AS bucket WHERE r.weekday IN (6, 7)
    UNION ALL SELECT 'MORNING' WHERE r.weekday BETWEEN 1 AND 5 AND r.start_time < time '12:00'
    UNION ALL SELECT 'AFTERNOON' WHERE r.weekday BETWEEN 1 AND 5 AND r.start_time < time '17:00' AND r.end_time > time '12:00'
    UNION ALL SELECT 'EVENING' WHERE r.weekday BETWEEN 1 AND 5 AND r.end_time > time '17:00'
) b
WHERE r.valid_to IS NULL OR r.valid_to >= current_date;
