-- Synthetic demo data for local development (DEV_SEED=true, never in production). All people are fictional.
-- Idempotent: every insert skips rows that already exist, so the repeatable migration can rerun safely.
-- Sign in with POST /api/v1/dev/token {"subject": "<auth_subject>"}.
--
--   demo-trainer         Michał Wiśniewski (trainer)          trener@example.com
--   demo-client-1        Katarzyna Nowak (client)             klient@example.com
--   demo-client-2        Piotr Zieliński (client)
--   demo-trainer-client  Anna Kowalczyk (trainer and client)  trener-klient@example.com
--   demo-other-trainer   Tomasz Lewandowski (trainer without relationships to the clients above)

INSERT INTO identity.app_user (id, auth_subject, email, display_name) VALUES
    ('11111111-1111-1111-1111-111111111111', 'demo-trainer', 'trener@example.com', 'Michał Wiśniewski'),
    ('22222222-2222-2222-2222-222222222221', 'demo-client-1', 'klient@example.com', 'Katarzyna Nowak'),
    ('22222222-2222-2222-2222-222222222222', 'demo-client-2', 'piotr@example.com', 'Piotr Zieliński'),
    ('22222222-2222-2222-2222-222222222223', 'demo-trainer-client', 'trener-klient@example.com', 'Anna Kowalczyk'),
    ('33333333-3333-3333-3333-333333333333', 'demo-other-trainer', 'inny-trener@example.com', 'Tomasz Lewandowski')
ON CONFLICT DO NOTHING;

INSERT INTO identity.trainer_profile (user_id, default_currency, session_buffer_minutes) VALUES
    ('11111111-1111-1111-1111-111111111111', 'PLN', 15),
    ('22222222-2222-2222-2222-222222222223', 'PLN', 0),
    ('33333333-3333-3333-3333-333333333333', 'PLN', 0)
ON CONFLICT DO NOTHING;

INSERT INTO identity.client_profile (user_id) VALUES
    ('22222222-2222-2222-2222-222222222221'),
    ('22222222-2222-2222-2222-222222222222'),
    ('22222222-2222-2222-2222-222222222223')
ON CONFLICT DO NOTHING;

INSERT INTO identity.trainer_client (id, trainer_id, client_id, status, origin, started_at) VALUES
    ('44444444-4444-4444-4444-444444444441', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222221', 'ACTIVE', 'INVITE_CODE', now() - interval '120 days'),
    ('44444444-4444-4444-4444-444444444442', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222', 'ACTIVE', 'CLIENT_REQUEST', now() - interval '30 days'),
    ('44444444-4444-4444-4444-444444444443', '11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222223', 'ACTIVE', 'TRAINER_ADDED', now() - interval '10 days')
ON CONFLICT DO NOTHING;

INSERT INTO identity.access_grant (id, relationship_id, scope)
SELECT v.id::uuid, v.rel::uuid, v.scope FROM (VALUES
    ('45454545-0000-0000-0000-000000000001', '44444444-4444-4444-4444-444444444441', 'HEALTH_QUESTIONNAIRE'),
    ('45454545-0000-0000-0000-000000000002', '44444444-4444-4444-4444-444444444441', 'MEASUREMENTS'),
    ('45454545-0000-0000-0000-000000000003', '44444444-4444-4444-4444-444444444441', 'WORKOUT_HISTORY'),
    ('45454545-0000-0000-0000-000000000004', '44444444-4444-4444-4444-444444444441', 'TECHNIQUE_VIDEOS'),
    ('45454545-0000-0000-0000-000000000005', '44444444-4444-4444-4444-444444444442', 'WORKOUT_HISTORY')
) AS v(id, rel, scope)
ON CONFLICT DO NOTHING;

INSERT INTO catalogue.gym (id, name, street, city, postal_code, time_zone, created_by) VALUES
    ('77777777-7777-7777-7777-777777777771', 'Siłownia Centrum', 'ul. Przykładowa 1', 'Warszawa', '00-001', 'Europe/Warsaw', '11111111-1111-1111-1111-111111111111')
ON CONFLICT DO NOTHING;

INSERT INTO catalogue.trainer_gym (trainer_id, gym_id) VALUES
    ('11111111-1111-1111-1111-111111111111', '77777777-7777-7777-7777-777777777771')
ON CONFLICT DO NOTHING;

INSERT INTO catalogue.trainer_listing (trainer_id, headline, bio, specialties, is_public) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Trening siłowy i technika', 'Przykładowy opis trenera.', ARRAY['strength', 'technique'], true)
ON CONFLICT DO NOTHING;

INSERT INTO catalogue.offer (id, trainer_id, kind, name, duration_minutes, price_minor, currency, sessions_per_period) VALUES
    ('66666666-6666-6666-6666-666666666661', '11111111-1111-1111-1111-111111111111', 'SINGLE_SESSION', 'Trening personalny 60 min', 60, 15000, 'PLN', NULL),
    ('66666666-6666-6666-6666-666666666662', '11111111-1111-1111-1111-111111111111', 'PACKAGE', 'Pakiet 8 treningów / miesiąc', 60, 100000, 'PLN', 8),
    ('66666666-6666-6666-6666-666666666663', '33333333-3333-3333-3333-333333333333', 'SINGLE_SESSION', 'Trening 60 min', 60, 12000, 'PLN', NULL)
ON CONFLICT DO NOTHING;

-- "Standard" preset (D7): <24 h 50 %, <12 h 100 %, no-show 100 %, grace 15 min, 1 free late cancellation per month.
INSERT INTO catalogue.policy_version (id, trainer_id, version, no_show_percent, grace_minutes, free_late_cancellations) VALUES
    ('55555555-5555-5555-5555-555555555551', '11111111-1111-1111-1111-111111111111', 1, 100, 15, 1),
    ('55555555-5555-5555-5555-555555555553', '33333333-3333-3333-3333-333333333333', 1, 100, 15, 1)
ON CONFLICT DO NOTHING;

INSERT INTO catalogue.policy_tier (policy_version_id, hours_before, fee_percent) VALUES
    ('55555555-5555-5555-5555-555555555551', 24, 50),
    ('55555555-5555-5555-5555-555555555551', 12, 100),
    ('55555555-5555-5555-5555-555555555553', 24, 50),
    ('55555555-5555-5555-5555-555555555553', 12, 100)
ON CONFLICT DO NOTHING;

-- Weekly availability Mon–Fri 07:00–20:00 (D9).
INSERT INTO scheduling.availability_rule (id, trainer_id, weekday, start_time, end_time, time_zone, gym_id, valid_from)
SELECT ('88888888-8888-8888-8888-88888888888' || d)::uuid, '11111111-1111-1111-1111-111111111111', d, '07:00', '20:00',
       'Europe/Warsaw', '77777777-7777-7777-7777-777777777771', DATE '2026-01-01'
FROM generate_series(1, 5) AS d
ON CONFLICT DO NOTHING;

-- Sessions relative to the first seed run: one confirmed tomorrow, one request the day after, one completed last week.
INSERT INTO scheduling.session (id, relationship_id, trainer_id, client_id, offer_id, status, starts_at, ends_at, blocked_until,
                                time_zone, gym_id, place_note, price_minor, currency, payment_kind, policy_version_id,
                                policy_accepted_at, created_by, operation_id, requested_at, confirmed_at, closed_at)
VALUES
    ('99999999-9999-9999-9999-999999999991', '44444444-4444-4444-4444-444444444441', '11111111-1111-1111-1111-111111111111',
     '22222222-2222-2222-2222-222222222221', '66666666-6666-6666-6666-666666666661', 'CONFIRMED',
     date_trunc('day', now()) + interval '1 day 8 hours', date_trunc('day', now()) + interval '1 day 9 hours',
     date_trunc('day', now()) + interval '1 day 9 hours 15 minutes', 'Europe/Warsaw', '77777777-7777-7777-7777-777777777771',
     'Siłownia Centrum', 15000, 'PLN', 'CASH', '55555555-5555-5555-5555-555555555551', now() - interval '5 days',
     '22222222-2222-2222-2222-222222222221', '99999999-0000-0000-0000-000000000001', now() - interval '5 days', now() - interval '4 days', NULL),
    ('99999999-9999-9999-9999-999999999992', '44444444-4444-4444-4444-444444444442', '11111111-1111-1111-1111-111111111111',
     '22222222-2222-2222-2222-222222222222', '66666666-6666-6666-6666-666666666661', 'REQUESTED',
     date_trunc('day', now()) + interval '2 days 10 hours', date_trunc('day', now()) + interval '2 days 11 hours',
     date_trunc('day', now()) + interval '2 days 11 hours', 'Europe/Warsaw', '77777777-7777-7777-7777-777777777771',
     'Siłownia Centrum', 15000, 'PLN', 'CASH', '55555555-5555-5555-5555-555555555551', now() - interval '1 hour',
     '22222222-2222-2222-2222-222222222222', '99999999-0000-0000-0000-000000000002', now() - interval '1 hour', NULL, NULL),
    ('99999999-9999-9999-9999-999999999993', '44444444-4444-4444-4444-444444444441', '11111111-1111-1111-1111-111111111111',
     '22222222-2222-2222-2222-222222222221', '66666666-6666-6666-6666-666666666661', 'COMPLETED',
     date_trunc('day', now()) - interval '6 days' + interval '8 hours', date_trunc('day', now()) - interval '6 days' + interval '9 hours',
     date_trunc('day', now()) - interval '6 days' + interval '9 hours 15 minutes', 'Europe/Warsaw', '77777777-7777-7777-7777-777777777771',
     'Siłownia Centrum', 15000, 'PLN', 'CASH', '55555555-5555-5555-5555-555555555551', now() - interval '12 days',
     '22222222-2222-2222-2222-222222222221', '99999999-0000-0000-0000-000000000003', now() - interval '12 days', now() - interval '11 days',
     date_trunc('day', now()) - interval '6 days' + interval '9 hours')
ON CONFLICT DO NOTHING;

-- The completed cash session is still unpaid and overdue.
INSERT INTO billing.charge (id, relationship_id, kind, settlement, session_id, amount_minor, currency, due_at, operation_id) VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', '44444444-4444-4444-4444-444444444441', 'SESSION', 'CASH',
     '99999999-9999-9999-9999-999999999993', 15000, 'PLN', date_trunc('day', now()) - interval '6 days' + interval '9 hours',
     'aaaaaaaa-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

INSERT INTO messaging.conversation (id, relationship_id) VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1', '44444444-4444-4444-4444-444444444441'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2', '44444444-4444-4444-4444-444444444442'),
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb3', '44444444-4444-4444-4444-444444444443')
ON CONFLICT DO NOTHING;
