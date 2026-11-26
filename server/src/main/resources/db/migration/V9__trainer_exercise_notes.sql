-- The trainer's own note and video on any exercise, including the global library ("Od trenera",
-- docs/plans/trainer-redesign.md). One row per trainer and exercise; the video is a media asset of that trainer.
CREATE TABLE training.exercise_trainer_note (
    trainer_id    uuid NOT NULL REFERENCES identity.trainer_profile,
    exercise_id   uuid NOT NULL REFERENCES training.exercise,
    note          text NOT NULL DEFAULT '' CHECK (length(note) <= 2000),
    -- No foreign key: the app refers to the video at once, while its upload may still be on the way (as for own exercises).
    demo_media_id uuid,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (trainer_id, exercise_id)
);
