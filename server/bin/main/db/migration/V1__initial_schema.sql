-- Server database: draft schema (PostgreSQL 16+)
--
-- Status: draft v0.1 · 2026-11-11 · proposal, not an agreed decision. See docs/database-design.md.
-- Intended to become the first Flyway migration (V1__initial_schema.sql) of the Ktor backend once it exists.
--
-- Conventions
--   * One PostgreSQL schema per backend module (architecture §6). A module writes only its own tables;
--     cross-module foreign keys are allowed for integrity, cross-module writes are not.
--   * Primary keys are UUIDs. Entities created offline on the phone (workout logs, sets, custom exercises,
--     media, messages) use the client-generated UUID, so a retried upload is the same row (dedup by PK).
--   * Instants are timestamptz (stored UTC). Where local time matters, an IANA zone is stored next to it.
--   * Money is bigint minor units + char(3) ISO 4217 currency, never numeric/float (contract Money).
--   * Enum-like columns are text + CHECK with UPPER_SNAKE values; the Kotlin enums in :contract map to them.
--     (CHECK is easier to migrate than CREATE TYPE … AS ENUM.)
--   * Weight in integer grams, distance in metres, durations in seconds, RPE numeric(3,1) — as in the app.
--   * Synced tables carry updated_at + revision for "changes since" pulls, and deleted_at tombstones.
--   * Immutable and append-only tables are protected by the forbid_update trigger.
--   * Health and private data never go into outbox payloads, job payloads, audit metadata or logs.

CREATE EXTENSION IF NOT EXISTS btree_gist;  -- uuid "=" inside EXCLUDE constraints
CREATE EXTENSION IF NOT EXISTS citext;      -- case-insensitive e-mail
CREATE EXTENSION IF NOT EXISTS pg_trgm;     -- exercise name search

CREATE SCHEMA identity;
CREATE SCHEMA catalogue;
CREATE SCHEMA coaching;
CREATE SCHEMA scheduling;
CREATE SCHEMA training;
CREATE SCHEMA messaging;
CREATE SCHEMA media;
CREATE SCHEMA billing;
CREATE SCHEMA platform;

-- Rows of immutable/append-only tables can be inserted, never changed.
CREATE FUNCTION platform.forbid_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only/immutable', TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME;
END $$;

-- =====================================================================================================
-- identity: accounts, roles, trainer–client relationships, access grants
-- =====================================================================================================

CREATE TABLE identity.app_user (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_subject    text NOT NULL UNIQUE,              -- IdP subject; the seeded demo id while auth is mocked
    email           citext UNIQUE,
    display_name    text NOT NULL CHECK (length(display_name) BETWEEN 1 AND 120),
    locale          text NOT NULL DEFAULT 'pl' CHECK (locale IN ('pl', 'en')),
    time_zone       text NOT NULL DEFAULT 'Europe/Warsaw',
    is_operator     boolean NOT NULL DEFAULT false,     -- G3 admin flag; operators get no health/chat access
    created_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz                         -- account deletion: anonymise, keep ledger rows
);

-- Holding a role = having the profile row. One user may hold both (G4).
CREATE TABLE identity.trainer_profile (
    user_id                 uuid PRIMARY KEY REFERENCES identity.app_user,
    default_currency        char(3) NOT NULL DEFAULT 'PLN',
    session_buffer_minutes  int NOT NULL DEFAULT 0 CHECK (session_buffer_minutes BETWEEN 0 AND 120),
    payment_account_ref     text,                       -- Stripe Connect account id later; null = cash only
    created_at              timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity.client_profile (
    user_id     uuid PRIMARY KEY REFERENCES identity.app_user,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- The trainer–client relationship: the unit of access, chat, billing and plans.
CREATE TABLE identity.trainer_client (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id  uuid NOT NULL REFERENCES identity.trainer_profile,
    client_id   uuid NOT NULL REFERENCES identity.client_profile,
    status      text NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'ENDED')),
    origin      text NOT NULL CHECK (origin IN ('INVITE_CODE', 'CLIENT_REQUEST', 'TRAINER_ADDED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    started_at  timestamptz,
    ended_at    timestamptz,
    CHECK (trainer_id <> client_id),
    CHECK ((status = 'ENDED') = (ended_at IS NOT NULL))
);
-- At most one open relationship per pair; an ended one may be followed by a new one.
CREATE UNIQUE INDEX trainer_client_open_pair ON identity.trainer_client (trainer_id, client_id)
    WHERE status <> 'ENDED';
CREATE INDEX trainer_client_client ON identity.trainer_client (client_id);

-- Scoped access a client gives the trainer; revoked when the relationship ends (architecture §4 GDPR).
CREATE TABLE identity.access_grant (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id  uuid NOT NULL REFERENCES identity.trainer_client,
    scope            text NOT NULL CHECK (scope IN ('HEALTH_QUESTIONNAIRE', 'MEASUREMENTS', 'WORKOUT_HISTORY', 'TECHNIQUE_VIDEOS')),
    granted_at       timestamptz NOT NULL DEFAULT now(),
    revoked_at       timestamptz
);
CREATE UNIQUE INDEX access_grant_active ON identity.access_grant (relationship_id, scope) WHERE revoked_at IS NULL;

CREATE TABLE identity.invite_code (
    code        text PRIMARY KEY,
    trainer_id  uuid NOT NULL REFERENCES identity.trainer_profile,
    expires_at  timestamptz NOT NULL,
    max_uses    int NOT NULL DEFAULT 1 CHECK (max_uses > 0),
    used_count  int NOT NULL DEFAULT 0 CHECK (used_count <= max_uses),
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- =====================================================================================================
-- catalogue: public listings, gyms, offers/prices, cancellation policy versions
-- =====================================================================================================

-- The only trainer data guests may see. Kept apart from identity so a public query cannot join into private data.
CREATE TABLE catalogue.trainer_listing (
    trainer_id    uuid PRIMARY KEY REFERENCES identity.trainer_profile,
    headline      text NOT NULL CHECK (length(headline) <= 120),
    bio           text CHECK (length(bio) <= 2000),
    specialties   text[] NOT NULL DEFAULT '{}',
    is_public     boolean NOT NULL DEFAULT false,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE catalogue.gym (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name           text NOT NULL,
    street         text NOT NULL,
    city           text NOT NULL,
    postal_code    text,
    latitude       numeric(9, 6),
    longitude      numeric(9, 6),
    time_zone      text NOT NULL DEFAULT 'Europe/Warsaw',
    opening_hours  jsonb,                  -- [{weekday, opens, closes}] in time_zone; display only
    is_public      boolean NOT NULL DEFAULT true,
    created_by     uuid REFERENCES identity.app_user,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE catalogue.trainer_gym (
    trainer_id  uuid NOT NULL REFERENCES identity.trainer_profile,
    gym_id      uuid NOT NULL REFERENCES catalogue.gym,
    PRIMARY KEY (trainer_id, gym_id)
);

-- Price list: service × duration (G7). Sessions snapshot the price, so an offer may be archived and replaced.
CREATE TABLE catalogue.offer (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id            uuid NOT NULL REFERENCES identity.trainer_profile,
    kind                  text NOT NULL CHECK (kind IN ('SINGLE_SESSION', 'PACKAGE')),
    name                  text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    duration_minutes      int NOT NULL CHECK (duration_minutes BETWEEN 15 AND 240),
    price_minor           bigint NOT NULL CHECK (price_minor >= 0),
    currency              char(3) NOT NULL,
    sessions_per_period   int CHECK (sessions_per_period > 0),   -- packages only (G13)
    seller                text NOT NULL DEFAULT 'TRAINER' CHECK (seller IN ('TRAINER')),          -- G12
    purpose               text NOT NULL DEFAULT 'PHYSICAL' CHECK (purpose IN ('PHYSICAL')),       -- §4: no digital tier
    is_public             boolean NOT NULL DEFAULT true,
    created_at            timestamptz NOT NULL DEFAULT now(),
    archived_at           timestamptz,
    CHECK ((kind = 'PACKAGE') = (sessions_per_period IS NOT NULL))
);
CREATE INDEX offer_trainer ON catalogue.offer (trainer_id) WHERE archived_at IS NULL;

CREATE TABLE catalogue.client_price_override (
    relationship_id  uuid NOT NULL REFERENCES identity.trainer_client,
    offer_id         uuid NOT NULL REFERENCES catalogue.offer,
    price_minor      bigint NOT NULL CHECK (price_minor >= 0),
    currency         char(3) NOT NULL,
    PRIMARY KEY (relationship_id, offer_id)
);

-- Immutable. Saving the policy editor inserts a new version (contract CancellationPolicy).
CREATE TABLE catalogue.policy_version (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id                  uuid NOT NULL REFERENCES identity.trainer_profile,
    version                     int NOT NULL CHECK (version > 0),
    no_show_percent             int NOT NULL CHECK (no_show_percent IN (0, 25, 50, 75, 100)),
    grace_minutes               int NOT NULL CHECK (grace_minutes BETWEEN 5 AND 30),
    free_late_cancellations     int NOT NULL CHECK (free_late_cancellations BETWEEN 0 AND 4),
    late_reschedule_is_cancel   boolean NOT NULL DEFAULT true,
    cash_fee_mode               text NOT NULL DEFAULT 'ADD_TO_NEXT_SESSION' CHECK (cash_fee_mode IN ('ADD_TO_NEXT_SESSION', 'DEBT_ONLY')),
    package_fee_mode            text NOT NULL DEFAULT 'USE_CREDIT' CHECK (package_fee_mode IN ('USE_CREDIT', 'CHARGE_MONEY')),
    client_note                 text CHECK (length(client_note) <= 300),
    created_at                  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (trainer_id, version)
);

CREATE TABLE catalogue.policy_tier (
    policy_version_id  uuid NOT NULL REFERENCES catalogue.policy_version,
    hours_before       int NOT NULL CHECK (hours_before BETWEEN 1 AND 168),
    fee_percent        int NOT NULL CHECK (fee_percent IN (0, 25, 50, 75, 100)),
    PRIMARY KEY (policy_version_id, hours_before)
);

CREATE TRIGGER policy_version_immutable BEFORE UPDATE OR DELETE ON catalogue.policy_version
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();
CREATE TRIGGER policy_tier_immutable BEFORE UPDATE OR DELETE ON catalogue.policy_tier
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();

-- Per-client override (architecture §3.1): exempt the client, or apply another of the trainer's versions.
CREATE TABLE catalogue.client_policy_override (
    relationship_id    uuid PRIMARY KEY REFERENCES identity.trainer_client,
    mode               text NOT NULL CHECK (mode IN ('EXEMPT', 'OTHER_POLICY')),
    policy_version_id  uuid REFERENCES catalogue.policy_version,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CHECK ((mode = 'OTHER_POLICY') = (policy_version_id IS NOT NULL))
);

-- =====================================================================================================
-- coaching: consents, health questionnaire, goals, trainer notes  (special-category data, GDPR Art. 9)
-- =====================================================================================================

-- Append-only: a withdrawal is a new row, so the history of what was agreed and when is kept.
CREATE TABLE coaching.consent_record (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           uuid NOT NULL REFERENCES identity.app_user,
    purpose           text NOT NULL CHECK (purpose IN ('TERMS', 'PRIVACY_POLICY', 'HEALTH_DATA', 'TECHNIQUE_VIDEO', 'MARKETING')),
    document_version  text NOT NULL,          -- e.g. 'demo-2026-11' (guest-views D13)
    action            text NOT NULL CHECK (action IN ('GRANTED', 'WITHDRAWN')),
    recorded_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX consent_record_user ON coaching.consent_record (user_id, purpose, recorded_at DESC);
CREATE TRIGGER consent_record_append_only BEFORE UPDATE OR DELETE ON coaching.consent_record
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();

-- One row per submitted version. Answers are encrypted by the API before insert (field-level encryption).
CREATE TABLE coaching.health_questionnaire (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    client_id          uuid NOT NULL REFERENCES identity.client_profile,
    version            int NOT NULL,
    form_version       text NOT NULL,          -- which question set was answered
    answers_encrypted  bytea NOT NULL,
    key_id             text NOT NULL,          -- encryption key reference (KMS later)
    submitted_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (client_id, version)
);

CREATE TABLE coaching.client_goal (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id  uuid NOT NULL REFERENCES identity.trainer_client,
    text             text NOT NULL CHECK (length(text) <= 500),
    target_date      date,
    achieved_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now()
);

-- Private trainer notes. Visible to the authoring trainer only.
CREATE TABLE coaching.coaching_note (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id  uuid NOT NULL REFERENCES identity.trainer_client,
    author_id        uuid NOT NULL REFERENCES identity.app_user,
    body             text NOT NULL CHECK (length(body) <= 5000),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz
);
CREATE INDEX coaching_note_relationship ON coaching.coaching_note (relationship_id, created_at DESC);

-- =====================================================================================================
-- scheduling: availability, blocks, sessions, reschedule negotiation, attendance
-- =====================================================================================================

-- Weekly working hours in the trainer's local time.
CREATE TABLE scheduling.availability_rule (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id  uuid NOT NULL REFERENCES identity.trainer_profile,
    weekday     smallint NOT NULL CHECK (weekday BETWEEN 1 AND 7),   -- ISO: 1 = Monday
    start_time  time NOT NULL,
    end_time    time NOT NULL,
    time_zone   text NOT NULL,
    gym_id      uuid REFERENCES catalogue.gym,
    valid_from  date NOT NULL,
    valid_to    date,
    CHECK (start_time < end_time),
    CHECK (valid_to IS NULL OR valid_from <= valid_to)
);
CREATE INDEX availability_rule_trainer ON scheduling.availability_rule (trainer_id);

-- Unavailability and private events (G5) in one table. Neither may overlap the trainer's other blocks.
CREATE TABLE scheduling.calendar_block (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    trainer_id  uuid NOT NULL REFERENCES identity.trainer_profile,
    kind        text NOT NULL CHECK (kind IN ('UNAVAILABLE', 'PRIVATE_EVENT')),
    title       text CHECK (length(title) <= 120),   -- private; never in the calendar feed for clients
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz,
    CHECK (starts_at < ends_at)
);
CREATE INDEX calendar_block_trainer ON scheduling.calendar_block USING gist (trainer_id, tstzrange(starts_at, ends_at))
    WHERE deleted_at IS NULL;

-- A booked coaching session. A booking request is a session in REQUESTED; it blocks nothing until CONFIRMED.
CREATE TABLE scheduling.session (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id     uuid NOT NULL REFERENCES identity.trainer_client,
    trainer_id          uuid NOT NULL REFERENCES identity.trainer_profile,   -- denormalised for the EXCLUDE constraints
    client_id           uuid NOT NULL REFERENCES identity.client_profile,
    offer_id            uuid REFERENCES catalogue.offer,
    status              text NOT NULL CHECK (status IN ('REQUESTED', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'COMPLETED',
                                                        'NO_SHOW', 'CANCELLED_BY_CLIENT', 'CANCELLED_BY_TRAINER')),
    starts_at           timestamptz NOT NULL,
    ends_at             timestamptz NOT NULL,
    blocked_until       timestamptz NOT NULL,     -- ends_at + trainer buffer, set by the server at confirm/move
    time_zone           text NOT NULL,            -- where the session happens, for display and month counting
    gym_id              uuid REFERENCES catalogue.gym,
    place_note          text CHECK (length(place_note) <= 200),
    -- Snapshots taken when the session is booked (G7, §3.2)
    price_minor         bigint NOT NULL CHECK (price_minor >= 0),
    currency            char(3) NOT NULL,
    payment_kind        text NOT NULL CHECK (payment_kind IN ('ONLINE', 'CASH', 'PACKAGE')),
    policy_version_id   uuid NOT NULL REFERENCES catalogue.policy_version,
    policy_accepted_at  timestamptz NOT NULL,     -- the client's (not pre-ticked) consent
    -- Concurrency: every change increments revision; quotes and reschedule requests are tied to one revision.
    revision            int NOT NULL DEFAULT 1,
    created_by          uuid NOT NULL REFERENCES identity.app_user,
    operation_id        uuid NOT NULL UNIQUE,     -- idempotent create from the app
    requested_at        timestamptz NOT NULL DEFAULT now(),   -- server receipt time
    confirmed_at        timestamptz,
    closed_at           timestamptz,              -- rejected / expired / cancelled / completed / no-show
    CHECK (starts_at < ends_at AND ends_at <= blocked_until),
    -- No double booking (architecture §7.2): enforced by the database, not by the app.
    CONSTRAINT session_no_trainer_overlap EXCLUDE USING gist
        (trainer_id WITH =, tstzrange(starts_at, blocked_until) WITH &&)
        WHERE (status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW')),
    CONSTRAINT session_no_client_overlap EXCLUDE USING gist
        (client_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
        WHERE (status IN ('CONFIRMED', 'COMPLETED', 'NO_SHOW'))
);
CREATE INDEX session_trainer_time ON scheduling.session (trainer_id, starts_at);
CREATE INDEX session_client_time ON scheduling.session (client_id, starts_at);
CREATE INDEX session_relationship ON scheduling.session (relationship_id, starts_at DESC);

-- History of every change (§7.2). Append-only.
CREATE TABLE scheduling.session_event (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id   uuid NOT NULL REFERENCES scheduling.session,
    revision     int NOT NULL,
    type         text NOT NULL CHECK (type IN ('REQUESTED', 'CONFIRMED', 'REJECTED', 'EXPIRED', 'MOVED', 'CANCELLED',
                                               'CHECKED_IN', 'NO_SHOW', 'ATTENDANCE_CORRECTED')),
    actor_id     uuid REFERENCES identity.app_user,      -- null = system (expiry job)
    old_starts_at timestamptz,
    old_ends_at   timestamptz,
    occurred_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX session_event_session ON scheduling.session_event (session_id, id);
CREATE TRIGGER session_event_append_only BEFORE UPDATE OR DELETE ON scheduling.session_event
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();

-- Separate lifecycle (§7.1): the session stays CONFIRMED at its old time until ACCEPTED commits.
CREATE TABLE scheduling.reschedule_request (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id         uuid NOT NULL REFERENCES scheduling.session,
    base_revision      int NOT NULL,              -- accept fails if the session has moved on
    proposed_by        uuid NOT NULL REFERENCES identity.app_user,
    proposer_role      text NOT NULL CHECK (proposer_role IN ('CLIENT', 'TRAINER')),
    proposed_starts_at timestamptz NOT NULL,
    proposed_ends_at   timestamptz NOT NULL,
    counter_starts_at  timestamptz,               -- set in COUNTER_PROPOSED
    counter_ends_at    timestamptz,
    status             text NOT NULL CHECK (status IN ('PENDING', 'COUNTER_PROPOSED', 'ACCEPTED', 'REJECTED', 'WITHDRAWN', 'EXPIRED')),
    received_at        timestamptz NOT NULL DEFAULT now(),   -- server receipt time decides the fee tier
    fee_quote_id       uuid,                      -- FK added after billing.fee_quote exists
    decided_by         uuid REFERENCES identity.app_user,
    decided_at         timestamptz,
    operation_id       uuid NOT NULL UNIQUE,
    CHECK (proposed_starts_at < proposed_ends_at),
    CHECK ((counter_starts_at IS NULL) = (counter_ends_at IS NULL))
);
CREATE UNIQUE INDEX reschedule_one_open ON scheduling.reschedule_request (session_id)
    WHERE status IN ('PENDING', 'COUNTER_PROPOSED');

-- Check-in / no-show. Append-only: a correction is a new row (latest wins) plus compensating ledger rows.
CREATE TABLE scheduling.attendance (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  uuid NOT NULL REFERENCES scheduling.session,
    outcome     text NOT NULL CHECK (outcome IN ('ATTENDED', 'NO_SHOW')),
    marked_by   uuid NOT NULL REFERENCES identity.app_user,
    marked_at   timestamptz NOT NULL DEFAULT now(),
    corrects_id uuid REFERENCES scheduling.attendance,
    reason      text CHECK (length(reason) <= 300)
);
CREATE INDEX attendance_session ON scheduling.attendance (session_id, marked_at DESC);
CREATE TRIGGER attendance_append_only BEFORE UPDATE OR DELETE ON scheduling.attendance
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();

-- =====================================================================================================
-- media: one pipeline for demo videos, technique videos, chat attachments, exercise images (§9.3)
-- =====================================================================================================

CREATE TABLE media.media_asset (
    id               uuid PRIMARY KEY,           -- client-generated; CreateUploadSessionRequest is idempotent by it
    owner_user_id    uuid NOT NULL REFERENCES identity.app_user,
    relationship_id  uuid REFERENCES identity.trainer_client,   -- set for private media (technique video, chat)
    kind             text NOT NULL CHECK (kind IN ('EXERCISE_DEMO', 'TECHNIQUE_VIDEO', 'CHAT_ATTACHMENT', 'EXERCISE_IMAGE')),
    state            text NOT NULL CHECK (state IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED')),
    content_type     text NOT NULL,
    size_bytes       bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 104857600),   -- 100 MB (D5)
    duration_ms      bigint CHECK (duration_ms <= 60000),                                   -- 60 s (D5)
    width            int,
    height           int,
    storage_key      text NOT NULL UNIQUE,       -- opaque: media/<id>, never names or user data
    thumbnail_key    text,
    failure_reason   text CHECK (failure_reason IN ('TOO_LARGE', 'TOO_LONG', 'UNSUPPORTED_TYPE', 'MISSING_OBJECT', 'PROCESSING_ERROR')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    ready_at         timestamptz,
    retain_until     timestamptz,                -- e.g. technique videos 90 days (§11)
    deleted_at       timestamptz,
    CHECK ((state = 'READY') = (ready_at IS NOT NULL)),
    CHECK (kind NOT IN ('TECHNIQUE_VIDEO', 'CHAT_ATTACHMENT') OR relationship_id IS NOT NULL)
);
CREATE INDEX media_asset_cleanup ON media.media_asset (state, created_at) WHERE state IN ('UPLOADING', 'PROCESSING');
CREATE INDEX media_asset_retention ON media.media_asset (retain_until) WHERE deleted_at IS NULL AND retain_until IS NOT NULL;

-- =====================================================================================================
-- training: exercises, plan templates and immutable versions, assignments, workout logs, measurements
-- =====================================================================================================

CREATE TABLE training.exercise (
    id                 uuid PRIMARY KEY,         -- client-generated for custom exercises
    owner_trainer_id   uuid REFERENCES identity.trainer_profile,   -- null = global library
    source             text NOT NULL,            -- 'free-exercise-db' | 'trainer'
    source_id          text,                     -- e.g. 'Barbell_Squat'; seed import upserts by (source, source_id)
    tracking_type      text NOT NULL CHECK (tracking_type IN ('WEIGHT_REPS', 'REPS', 'TIME', 'DISTANCE_TIME')),
    equipment          text NOT NULL,            -- contract Equipment
    primary_muscles    text[] NOT NULL DEFAULT '{}',   -- contract MuscleGroup values
    secondary_muscles  text[] NOT NULL DEFAULT '{}',
    featured           boolean NOT NULL DEFAULT false,
    status             text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'HIDDEN')),   -- never hard-deleted
    demo_media_id      uuid REFERENCES media.media_asset,
    revision           int NOT NULL DEFAULT 1,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),   -- drives the app's updatedSince sync
    UNIQUE (source, source_id),
    CHECK ((source = 'trainer') = (owner_trainer_id IS NOT NULL))
);
CREATE INDEX exercise_sync ON training.exercise (owner_trainer_id, updated_at);

-- Localised names and search aliases. search_text is produced by contract SearchText (lower-case, no diacritics).
CREATE TABLE training.exercise_name (
    exercise_id  uuid NOT NULL REFERENCES training.exercise,
    locale       text NOT NULL CHECK (locale IN ('pl', 'en')),
    name         text NOT NULL,
    is_alias     boolean NOT NULL DEFAULT false,
    search_text  text NOT NULL,
    PRIMARY KEY (exercise_id, locale, name)
);
CREATE INDEX exercise_name_search ON training.exercise_name USING gin (search_text gin_trgm_ops);

CREATE TABLE training.exercise_instruction (
    exercise_id  uuid NOT NULL REFERENCES training.exercise,
    locale       text NOT NULL CHECK (locale IN ('pl', 'en')),
    steps        text[] NOT NULL,
    PRIMARY KEY (exercise_id, locale)
);

CREATE TABLE training.exercise_image (
    exercise_id  uuid NOT NULL REFERENCES training.exercise,
    position     smallint NOT NULL,
    media_id     uuid NOT NULL REFERENCES media.media_asset,
    PRIMARY KEY (exercise_id, position)
);

CREATE TABLE training.plan_template (
    id                uuid PRIMARY KEY,
    owner_trainer_id  uuid NOT NULL REFERENCES identity.trainer_profile,
    name              text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    latest_version    int NOT NULL DEFAULT 0,
    archived_at       timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX plan_template_owner ON training.plan_template (owner_trainer_id) WHERE archived_at IS NULL;

-- Immutable (PlanVersionDto). Publishing locks the template row, checks baseVersion, inserts number = latest + 1.
CREATE TABLE training.plan_version (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id       uuid NOT NULL REFERENCES training.plan_template,
    number            int NOT NULL CHECK (number > 0),
    based_on_version  int,
    name              text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    tags              text[] NOT NULL DEFAULT '{}',
    created_by        uuid NOT NULL REFERENCES identity.app_user,
    created_at        timestamptz NOT NULL DEFAULT now(),
    operation_id      uuid NOT NULL UNIQUE,      -- PublishPlanRequest.operationId: a retry returns the same version
    UNIQUE (template_id, number)
);

-- Day/exercise/set ids come from the app draft and are kept across versions, so they are unique only
-- within one version: the keys are (plan_version_id, id). Workout logs reference them the same way.
CREATE TABLE training.plan_day (
    plan_version_id  uuid NOT NULL REFERENCES training.plan_version,
    id               text NOT NULL,
    position         smallint NOT NULL,
    name             text NOT NULL,
    PRIMARY KEY (plan_version_id, id),
    UNIQUE (plan_version_id, position)
);

CREATE TABLE training.planned_exercise (
    plan_version_id  uuid NOT NULL,
    id               text NOT NULL,
    day_id           text NOT NULL,
    position         smallint NOT NULL,
    exercise_id      uuid NOT NULL REFERENCES training.exercise,
    superset_group   text,
    rest_seconds     int CHECK (rest_seconds BETWEEN 0 AND 3600),
    rpe_enabled      boolean NOT NULL DEFAULT false,
    accessory        boolean NOT NULL DEFAULT false,
    tempo            text CHECK (length(tempo) <= 16),
    note             text CHECK (length(note) <= 500),
    PRIMARY KEY (plan_version_id, id),
    UNIQUE (plan_version_id, day_id, position),
    FOREIGN KEY (plan_version_id, day_id) REFERENCES training.plan_day
);
CREATE INDEX planned_exercise_exercise ON training.planned_exercise (exercise_id);   -- "in use" lock on tracking_type

CREATE TABLE training.planned_set (
    plan_version_id        uuid NOT NULL,
    id                     text NOT NULL,
    planned_exercise_id    text NOT NULL,
    position               smallint NOT NULL,
    type                   text NOT NULL DEFAULT 'WORKING' CHECK (type IN ('WARM_UP', 'WORKING', 'DROP')),
    reps_min               int CHECK (reps_min BETWEEN 1 AND 100),
    reps_max               int CHECK (reps_max BETWEEN 1 AND 100),
    weight_grams           int CHECK (weight_grams BETWEEN 0 AND 1000000),
    duration_seconds       int CHECK (duration_seconds > 0),
    distance_meters        int CHECK (distance_meters > 0),
    rpe                    numeric(3, 1) CHECK (rpe BETWEEN 1 AND 10 AND rpe * 2 = trunc(rpe * 2)),
    rest_seconds_override  int CHECK (rest_seconds_override BETWEEN 0 AND 3600),
    PRIMARY KEY (plan_version_id, id),
    UNIQUE (plan_version_id, planned_exercise_id, position),
    FOREIGN KEY (plan_version_id, planned_exercise_id) REFERENCES training.planned_exercise,
    CHECK (reps_min IS NULL OR reps_max IS NULL OR reps_min <= reps_max)
);

CREATE TRIGGER plan_version_immutable BEFORE UPDATE OR DELETE ON training.plan_version
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();
CREATE TRIGGER plan_day_immutable BEFORE UPDATE OR DELETE ON training.plan_day
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();
CREATE TRIGGER planned_exercise_immutable BEFORE UPDATE OR DELETE ON training.planned_exercise
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();
CREATE TRIGGER planned_set_immutable BEFORE UPDATE OR DELETE ON training.planned_set
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();

-- A plan version given to a client. Moving to a newer version is a new row; the old one becomes REPLACED.
-- Team decision: a client has at most one ACTIVE plan at a time (across all trainers).
CREATE TABLE training.assigned_plan (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id  uuid NOT NULL REFERENCES identity.trainer_client,
    client_id        uuid NOT NULL REFERENCES identity.client_profile,   -- denormalised for the one-active rule
    plan_version_id  uuid NOT NULL REFERENCES training.plan_version,
    session_id       uuid REFERENCES scheduling.session,   -- optional pin to one session
    status           text NOT NULL CHECK (status IN ('ACTIVE', 'REPLACED', 'ENDED')),
    starts_on        date,
    ends_on          date,
    assigned_by      uuid NOT NULL REFERENCES identity.app_user,
    assigned_at      timestamptz NOT NULL DEFAULT now(),
    operation_id     uuid NOT NULL UNIQUE
);
CREATE INDEX assigned_plan_relationship ON training.assigned_plan (relationship_id);
CREATE UNIQUE INDEX assigned_plan_one_active ON training.assigned_plan (client_id) WHERE status = 'ACTIVE';

-- A workout the client did. id is generated on the phone; the logger works offline (§9.2).
CREATE TABLE training.workout_log (
    id                uuid PRIMARY KEY,
    client_id         uuid NOT NULL REFERENCES identity.client_profile,
    session_id        uuid REFERENCES scheduling.session,          -- optional; independent workouts allowed
    assigned_plan_id  uuid REFERENCES training.assigned_plan,
    plan_version_id   uuid,
    plan_day_id       text,
    name              text NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    state             text NOT NULL CHECK (state IN ('IN_PROGRESS', 'FINISHED', 'DISCARDED')),
    started_at        timestamptz NOT NULL,
    finished_at       timestamptz,
    time_zone         text NOT NULL,
    place             text CHECK (length(place) <= 120),
    note              text CHECK (length(note) <= 2000),
    revision          int NOT NULL DEFAULT 1,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    deleted_at        timestamptz,
    FOREIGN KEY (plan_version_id, plan_day_id) REFERENCES training.plan_day,
    CHECK ((plan_day_id IS NULL) OR (plan_version_id IS NOT NULL)),
    CHECK ((state = 'FINISHED') = (finished_at IS NOT NULL))
);
CREATE INDEX workout_log_client ON training.workout_log (client_id, started_at DESC);
CREATE INDEX workout_log_sync ON training.workout_log (client_id, updated_at);

-- One exercise inside a workout (the app's LoggedExercise / exercise_entry_id).
CREATE TABLE training.workout_exercise (
    id                   uuid PRIMARY KEY,
    workout_log_id       uuid NOT NULL REFERENCES training.workout_log,
    position             smallint NOT NULL,
    exercise_id          uuid NOT NULL REFERENCES training.exercise,
    planned_exercise_id  text,                 -- within workout_log.plan_version_id
    superset_group       text,
    note                 text CHECK (length(note) <= 500),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    deleted_at           timestamptz
);
CREATE INDEX workout_exercise_log ON training.workout_exercise (workout_log_id);

-- One set. Client-generated id → retries never duplicate. Conflicts are resolved per set by revision.
CREATE TABLE training.set_log (
    id                   uuid PRIMARY KEY,
    workout_exercise_id  uuid NOT NULL REFERENCES training.workout_exercise,
    workout_log_id       uuid NOT NULL REFERENCES training.workout_log,   -- denormalised for history queries
    exercise_id          uuid NOT NULL REFERENCES training.exercise,      -- denormalised for PRs and charts
    position             smallint NOT NULL,
    type                 text NOT NULL CHECK (type IN ('WARM_UP', 'WORKING', 'DROP')),
    planned_set_id       text,
    weight_grams         int CHECK (weight_grams BETWEEN 0 AND 1000000),
    reps                 int CHECK (reps BETWEEN 0 AND 1000),
    rpe                  numeric(3, 1) CHECK (rpe BETWEEN 1 AND 10),
    duration_seconds     int CHECK (duration_seconds >= 0),
    distance_meters      int CHECK (distance_meters >= 0),
    completed_at         timestamptz,
    technique_media_id   uuid REFERENCES media.media_asset,
    revision             int NOT NULL DEFAULT 1,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    deleted_at           timestamptz
);
CREATE INDEX set_log_workout ON training.set_log (workout_log_id);
-- "Last time 80 kg × 6" and PRs: working sets of one exercise, newest first.
CREATE INDEX set_log_exercise_history ON training.set_log (exercise_id, completed_at DESC)
    WHERE type = 'WORKING' AND deleted_at IS NULL;

-- Private. Values in thousandths of the kind's unit (app MeasurementKind: BODY_WEIGHT kg, WAIST/HIPS cm).
CREATE TABLE training.body_measurement (
    id                 uuid PRIMARY KEY,
    client_id          uuid NOT NULL REFERENCES identity.client_profile,
    kind               text NOT NULL CHECK (kind IN ('BODY_WEIGHT', 'WAIST', 'HIPS')),
    value_thousandths  bigint NOT NULL CHECK (value_thousandths > 0),
    measured_at        timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL DEFAULT now(),
    deleted_at         timestamptz
);
CREATE INDEX body_measurement_client ON training.body_measurement (client_id, kind, measured_at DESC);

-- PRs are derived, never stored by hand (§10.5): best working set per exercise and client.
CREATE VIEW training.personal_record AS
SELECT DISTINCT ON (w.client_id, s.exercise_id)
       w.client_id, s.exercise_id, s.id AS set_log_id, s.weight_grams, s.reps, s.completed_at
FROM training.set_log s
JOIN training.workout_log w ON w.id = s.workout_log_id
WHERE s.type = 'WORKING' AND s.deleted_at IS NULL AND w.deleted_at IS NULL AND w.state = 'FINISHED'
  AND s.weight_grams IS NOT NULL AND s.reps > 0
ORDER BY w.client_id, s.exercise_id, s.weight_grams DESC, s.reps DESC, s.completed_at;

-- =====================================================================================================
-- messaging: 1:1 chat per relationship, workout comments, technique-video review
-- =====================================================================================================

CREATE TABLE messaging.conversation (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id  uuid NOT NULL UNIQUE REFERENCES identity.trainer_client,
    last_seq         bigint NOT NULL DEFAULT 0,      -- UPDATE … SET last_seq = last_seq + 1 RETURNING assigns seq
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE messaging.message (
    id               uuid PRIMARY KEY,               -- client-generated: resending is idempotent
    conversation_id  uuid NOT NULL REFERENCES messaging.conversation,
    seq              bigint NOT NULL,                -- server order; reconnect fetches seq > last seen
    sender_id        uuid NOT NULL REFERENCES identity.app_user,
    body             text CHECK (length(body) <= 4000),
    -- Optional context: a comment on a workout, a set, or a technique video under review
    workout_log_id   uuid REFERENCES training.workout_log,
    set_log_id       uuid REFERENCES training.set_log,
    created_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz,
    UNIQUE (conversation_id, seq)
);

CREATE TABLE messaging.message_attachment (
    message_id  uuid NOT NULL REFERENCES messaging.message,
    position    smallint NOT NULL,
    media_id    uuid NOT NULL REFERENCES media.media_asset,
    PRIMARY KEY (message_id, position)
);

CREATE TABLE messaging.read_marker (
    conversation_id  uuid NOT NULL REFERENCES messaging.conversation,
    user_id          uuid NOT NULL REFERENCES identity.app_user,
    last_read_seq    bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (conversation_id, user_id)
);

-- =====================================================================================================
-- billing: append-only ledger, fee quotes and decisions, packages and credits (§8)
-- =====================================================================================================

-- A server quote shown before a fee-bearing action; valid for one session revision until expires_at.
CREATE TABLE billing.fee_quote (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id              uuid NOT NULL REFERENCES scheduling.session,
    session_revision        int NOT NULL,
    action                  text NOT NULL CHECK (action IN ('CLIENT_CANCEL', 'LATE_RESCHEDULE', 'NO_SHOW')),
    policy_version_id       uuid NOT NULL REFERENCES catalogue.policy_version,
    received_at             timestamptz NOT NULL,      -- server time the tier was computed for
    tier_hours_before       int,
    fee_percent             int NOT NULL CHECK (fee_percent BETWEEN 0 AND 100),
    amount_minor            bigint NOT NULL CHECK (amount_minor >= 0),
    currency                char(3) NOT NULL,
    uses_free_cancellation  boolean NOT NULL,
    expires_at              timestamptz NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX fee_quote_session ON billing.fee_quote (session_id, created_at DESC);

ALTER TABLE scheduling.reschedule_request
    ADD CONSTRAINT reschedule_fee_quote_fk FOREIGN KEY (fee_quote_id) REFERENCES billing.fee_quote;

-- What was actually decided for a fee-bearing event (§10.5 FEE_DECISION). Waivers are full only for now (D8).
CREATE TABLE billing.fee_decision (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id              uuid NOT NULL REFERENCES scheduling.session,
    quote_id                uuid REFERENCES billing.fee_quote,
    action                  text NOT NULL CHECK (action IN ('CLIENT_CANCEL', 'LATE_RESCHEDULE', 'NO_SHOW', 'TRAINER_CANCEL')),
    received_at             timestamptz NOT NULL,
    policy_version_id       uuid REFERENCES catalogue.policy_version,
    fee_percent             int NOT NULL CHECK (fee_percent BETWEEN 0 AND 100),
    quoted_minor            bigint NOT NULL CHECK (quoted_minor >= 0),
    waived_minor            bigint NOT NULL DEFAULT 0 CHECK (waived_minor >= 0 AND waived_minor <= quoted_minor),
    currency                char(3) NOT NULL,
    used_free_cancellation  boolean NOT NULL DEFAULT false,
    exempt                  boolean NOT NULL DEFAULT false,   -- client_policy_override EXEMPT
    reason                  text CHECK (length(reason) <= 300),
    decided_by              uuid REFERENCES identity.app_user,   -- null = automatic (fee 0 / free cancellation)
    decided_at              timestamptz NOT NULL DEFAULT now(),
    operation_id            uuid NOT NULL UNIQUE,
    CHECK (action <> 'TRAINER_CANCEL' OR quoted_minor = 0)       -- trainer cancellations never cost the client
);
CREATE INDEX fee_decision_session ON billing.fee_decision (session_id);
CREATE TRIGGER fee_decision_append_only BEFORE UPDATE OR DELETE ON billing.fee_decision
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();

CREATE TABLE billing.package_agreement (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id      uuid NOT NULL REFERENCES identity.trainer_client,
    offer_id             uuid NOT NULL REFERENCES catalogue.offer,
    sessions_per_period  int NOT NULL CHECK (sessions_per_period > 0),
    price_minor          bigint NOT NULL CHECK (price_minor >= 0),
    currency             char(3) NOT NULL,
    status               text NOT NULL CHECK (status IN ('ACTIVE', 'ENDED')),
    started_at           timestamptz NOT NULL DEFAULT now(),
    ended_at             timestamptz,
    withdrawal_info_accepted_at  timestamptz,   -- 14-day withdrawal notice / early-start consent (§4)
    operation_id         uuid NOT NULL UNIQUE
);

-- One paid period of a package. Credits exist only once payment is confirmed (granted_at).
CREATE TABLE billing.billing_period (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    agreement_id          uuid NOT NULL REFERENCES billing.package_agreement,
    period_start          date NOT NULL,
    period_end            date NOT NULL,
    credit_units_granted  int NOT NULL CHECK (credit_units_granted >= 0),   -- 100 units = 1 session
    granted_at            timestamptz,
    UNIQUE (agreement_id, period_start),
    CHECK (period_start < period_end)
);

-- Confirming a package session reserves 100 units; the outcome consumes 0–100 and releases the rest.
CREATE TABLE billing.credit_reservation (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_period_id  uuid NOT NULL REFERENCES billing.billing_period,
    session_id         uuid NOT NULL REFERENCES scheduling.session,
    reserved_units     int NOT NULL DEFAULT 100 CHECK (reserved_units BETWEEN 0 AND 100),
    consumed_units     int CHECK (consumed_units BETWEEN 0 AND reserved_units),
    status             text NOT NULL CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    settled_at         timestamptz,
    CHECK ((status = 'CONSUMED') = (consumed_units IS NOT NULL))
);
CREATE UNIQUE INDEX credit_reservation_one_open ON billing.credit_reservation (session_id) WHERE status = 'RESERVED';

-- Team decision (2026-11-11): credits never expire. Unused credits carry over to the next month, and so on,
-- so the balance is per agreement, not per month. Reservations draw from the oldest period that still has units.
CREATE VIEW billing.package_credit_balance AS
SELECT pa.id AS agreement_id, pa.relationship_id,
       COALESCE(g.granted, 0) AS granted_units,
       COALESCE(u.used, 0)    AS used_units,       -- consumed + currently reserved
       COALESCE(g.granted, 0) - COALESCE(u.used, 0) AS available_units
FROM billing.package_agreement pa
LEFT JOIN (SELECT agreement_id, sum(credit_units_granted) AS granted
           FROM billing.billing_period WHERE granted_at IS NOT NULL GROUP BY agreement_id) g
       ON g.agreement_id = pa.id
LEFT JOIN (SELECT bp.agreement_id,
                  sum(CASE cr.status WHEN 'RESERVED' THEN cr.reserved_units
                                     WHEN 'CONSUMED' THEN cr.consumed_units ELSE 0 END) AS used
           FROM billing.credit_reservation cr
           JOIN billing.billing_period bp ON bp.id = cr.billing_period_id
           GROUP BY bp.agreement_id) u
       ON u.agreement_id = pa.id;

CREATE TABLE billing.invoice_ref (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider     text NOT NULL,              -- e.g. 'fakturownia'
    external_id  text NOT NULL,
    number       text,
    status       text NOT NULL CHECK (status IN ('REQUESTED', 'ISSUED', 'FAILED', 'CORRECTED')),
    issued_at    timestamptz,
    UNIQUE (provider, external_id)
);

-- What is owed. Append-only; a correction is an adjustment, never an edit.
CREATE TABLE billing.charge (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id    uuid NOT NULL REFERENCES identity.trainer_client,
    kind               text NOT NULL CHECK (kind IN ('SESSION', 'LATE_CANCELLATION_FEE', 'LATE_RESCHEDULE_FEE', 'NO_SHOW_FEE', 'PACKAGE_PERIOD')),
    settlement         text NOT NULL CHECK (settlement IN ('ONLINE', 'CASH', 'PACKAGE_CREDIT')),
    session_id         uuid REFERENCES scheduling.session,
    fee_decision_id    uuid REFERENCES billing.fee_decision,
    billing_period_id  uuid REFERENCES billing.billing_period,
    amount_minor       bigint NOT NULL CHECK (amount_minor > 0),
    currency           char(3) NOT NULL,
    due_at             timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    operation_id       uuid NOT NULL UNIQUE,
    CHECK (kind <> 'PACKAGE_PERIOD' OR billing_period_id IS NOT NULL),
    CHECK (kind NOT LIKE '%FEE' OR fee_decision_id IS NOT NULL)
);
CREATE INDEX charge_relationship ON billing.charge (relationship_id, due_at);
CREATE INDEX charge_session ON billing.charge (session_id);

-- Money received. Amount never changes; status only moves PENDING → SUCCEEDED/FAILED (webhook or cash record).
CREATE TABLE billing.payment (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id   uuid NOT NULL REFERENCES identity.trainer_client,
    method            text NOT NULL CHECK (method IN ('CASH', 'CARD', 'BLIK', 'PRZELEWY24', 'TEST_MODE')),
    status            text NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    amount_minor      bigint NOT NULL CHECK (amount_minor > 0),
    currency          char(3) NOT NULL,
    provider          text,                      -- 'stripe' | 'fake'; null for cash
    provider_ref      text,                      -- payment intent id
    recorded_by       uuid REFERENCES identity.app_user,   -- the trainer, for cash
    created_at        timestamptz NOT NULL DEFAULT now(),
    settled_at        timestamptz,               -- authoritative confirmation time
    operation_id      uuid NOT NULL UNIQUE,
    UNIQUE (provider, provider_ref),
    CHECK ((method = 'CASH') = (provider IS NULL)),
    CHECK ((status = 'SUCCEEDED') = (settled_at IS NOT NULL))
);
CREATE INDEX payment_relationship ON billing.payment (relationship_id, created_at DESC);

-- Which charges a payment settles. Sum per payment ≤ payment, sum per charge ≤ charge − adjustments
-- (checked by the Billing module in the same transaction, with the charge rows locked).
CREATE TABLE billing.allocation (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id    uuid NOT NULL REFERENCES billing.payment,
    charge_id     uuid NOT NULL REFERENCES billing.charge,
    amount_minor  bigint NOT NULL CHECK (amount_minor <> 0),   -- negative = reversal of an earlier allocation
    created_at    timestamptz NOT NULL DEFAULT now(),
    operation_id  uuid NOT NULL UNIQUE
);
CREATE INDEX allocation_charge ON billing.allocation (charge_id);
CREATE INDEX allocation_payment ON billing.allocation (payment_id);

CREATE TABLE billing.refund (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id    uuid NOT NULL REFERENCES billing.payment,
    amount_minor  bigint NOT NULL CHECK (amount_minor > 0),
    status        text NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    reason        text NOT NULL CHECK (reason IN ('TRAINER_CANCELLED', 'WITHDRAWAL', 'CORRECTION')),
    provider_ref  text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    settled_at    timestamptz,
    operation_id  uuid NOT NULL UNIQUE
);

-- Reduces what a charge asks for: waivers and corrections (e.g. a no-show marked by mistake).
CREATE TABLE billing.adjustment (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    charge_id     uuid NOT NULL REFERENCES billing.charge,
    kind          text NOT NULL CHECK (kind IN ('WAIVER', 'CORRECTION')),
    amount_minor  bigint NOT NULL CHECK (amount_minor > 0),
    reason        text CHECK (length(reason) <= 300),
    created_by    uuid NOT NULL REFERENCES identity.app_user,
    created_at    timestamptz NOT NULL DEFAULT now(),
    operation_id  uuid NOT NULL UNIQUE
);
CREATE INDEX adjustment_charge ON billing.adjustment (charge_id);

CREATE TRIGGER charge_append_only BEFORE UPDATE OR DELETE ON billing.charge
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();
CREATE TRIGGER allocation_append_only BEFORE UPDATE OR DELETE ON billing.allocation
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();
CREATE TRIGGER adjustment_append_only BEFORE UPDATE OR DELETE ON billing.adjustment
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();

-- Charges are append-only, so the invoice issued later is linked here rather than written onto the charge.
CREATE TABLE billing.charge_invoice (
    charge_id       uuid PRIMARY KEY REFERENCES billing.charge,
    invoice_ref_id  uuid NOT NULL REFERENCES billing.invoice_ref
);

-- Webhook events, stored once by provider event id, processed once.
CREATE TABLE billing.provider_event (
    provider      text NOT NULL,
    event_id      text NOT NULL,
    type          text NOT NULL,
    payload       jsonb NOT NULL,
    received_at   timestamptz NOT NULL DEFAULT now(),
    processed_at  timestamptz,
    PRIMARY KEY (provider, event_id)
);

-- What is still open on each charge. Overdue = past due and not fully settled (§8).
CREATE VIEW billing.charge_balance AS
SELECT c.id AS charge_id, c.relationship_id, c.currency, c.due_at,
       c.amount_minor
         - COALESCE((SELECT sum(a.amount_minor) FROM billing.adjustment a WHERE a.charge_id = c.id), 0)
         - COALESCE((SELECT sum(al.amount_minor) FROM billing.allocation al
                     JOIN billing.payment p ON p.id = al.payment_id AND p.status = 'SUCCEEDED'
                     WHERE al.charge_id = c.id), 0) AS open_minor
FROM billing.charge c
WHERE c.settlement <> 'PACKAGE_CREDIT';

-- The only financial fact the client list may show (CLAUDE.md §7): a yes/no flag.
CREATE VIEW billing.relationship_overdue AS
SELECT relationship_id, bool_or(open_minor > 0 AND due_at < now()) AS has_overdue
FROM billing.charge_balance
GROUP BY relationship_id;

-- =====================================================================================================
-- platform: outbox, jobs, idempotency, audit, push tokens, calendar feed
-- =====================================================================================================

-- Written in the same transaction as the business change; the worker delivers it (§5).
CREATE TABLE platform.outbox_event (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type  text NOT NULL,           -- 'session', 'payment', 'message', …
    aggregate_id    uuid NOT NULL,
    type            text NOT NULL,           -- 'session.confirmed', …
    payload         jsonb NOT NULL,          -- ids only: no health data, no message text
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz
);
CREATE INDEX outbox_unpublished ON platform.outbox_event (id) WHERE published_at IS NULL;

-- Delayed and retried work: reminders, quote expiry, media checks, reconciliation.
-- Pollers use SELECT … FOR UPDATE SKIP LOCKED.
CREATE TABLE platform.job (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind          text NOT NULL,
    dedupe_key    text UNIQUE,               -- e.g. 'reminder:<session>:<revision>:24h'
    payload       jsonb NOT NULL DEFAULT '{}',
    run_at        timestamptz NOT NULL,
    status        text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    attempts      int NOT NULL DEFAULT 0,
    locked_until  timestamptz,
    last_error    text,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX job_due ON platform.job (run_at) WHERE status = 'PENDING';

-- Generic replay protection for endpoints whose result is not a single row with an operation_id.
CREATE TABLE platform.idempotency_key (
    user_id        uuid NOT NULL REFERENCES identity.app_user,
    operation_id   uuid NOT NULL,
    endpoint       text NOT NULL,
    request_hash   text NOT NULL,
    response_code  int,
    response_body  jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, operation_id)
);

-- Health-data reads, denied access, financial corrections, operator actions. Append-only.
CREATE TABLE platform.audit_record (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id         uuid REFERENCES identity.app_user,
    action           text NOT NULL,          -- 'HEALTH_READ', 'ACCESS_DENIED', 'ATTENDANCE_CORRECTED', …
    subject_type     text NOT NULL,
    subject_id       uuid,
    relationship_id  uuid,
    outcome          text NOT NULL CHECK (outcome IN ('ALLOWED', 'DENIED')),
    occurred_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_subject ON platform.audit_record (subject_type, subject_id, occurred_at DESC);
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON platform.audit_record
    FOR EACH ROW EXECUTE FUNCTION platform.forbid_update();

CREATE TABLE platform.push_token (
    token       text PRIMARY KEY,
    user_id     uuid NOT NULL REFERENCES identity.app_user,
    platform    text NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Secret, revocable webcal link (G5). Only the hash is stored.
CREATE TABLE platform.calendar_feed (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES identity.app_user,
    token_hash  text NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz
);
