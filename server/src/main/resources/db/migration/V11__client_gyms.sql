-- The gyms a client attends ("Moje siłownie" in the client profile). The client chooses them; their trainers read
-- them in the client card (docs/plans/trainer-redesign.md). Only public gyms of the catalogue.
CREATE TABLE identity.client_gym (
    client_id  uuid NOT NULL REFERENCES identity.client_profile,
    gym_id     uuid NOT NULL REFERENCES catalogue.gym,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, gym_id)
);
