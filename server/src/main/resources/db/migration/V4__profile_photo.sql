-- Profile photos (trainers and clients).
--
-- Status: proposal for team review. One small image per user, either uploaded by the user or copied once from the
-- Google account's picture at sign-in. An uploaded photo always wins: a Google copy never replaces it. Photos are
-- small (≤ 2 MB, resized by the app), so they live in the database instead of the media pipeline (which is built for
-- technique videos with processing and retention). Not special-category data, but still served only to signed-in
-- users who may see the person (self, a trainer's public profile, or a trainer–client relationship).

CREATE TABLE identity.profile_photo (
    user_id       uuid PRIMARY KEY REFERENCES identity.app_user,
    content_type  text NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    bytes         bytea NOT NULL CHECK (octet_length(bytes) BETWEEN 1 AND 2097152),
    source        text NOT NULL CHECK (source IN ('UPLOAD', 'GOOGLE')),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
