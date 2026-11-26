-- Photos about the user beyond the profile photo ("Zdjęcia" in the trainer profile, docs/plans/trainer-redesign.md).
-- Same storage and visibility as identity.profile_photo (V4): small images resized by the app, in the database, seen
-- by the person, anyone for a trainer (their public face) and the other side of a trainer–client relationship.
-- At most 9 per user (checked by the server).
CREATE TABLE identity.profile_gallery_photo (
    id            uuid PRIMARY KEY,
    user_id       uuid NOT NULL REFERENCES identity.app_user,
    content_type  text NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    bytes         bytea NOT NULL CHECK (octet_length(bytes) BETWEEN 1 AND 2097152),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX profile_gallery_photo_user ON identity.profile_gallery_photo (user_id, created_at);
