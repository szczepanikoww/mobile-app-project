-- Client-entered private information (birth date, body size, goal, injuries, movement limitations).
--
-- Status: proposal for team review (docs/features/trainer-backend.md, decision TB1). The app's trainer views show
-- this information (ClientInformation); the schema draft had no table for it. The client enters it; a trainer reads
-- it only through an ACTIVE relationship with the client's HEALTH_QUESTIONNAIRE grant, and every read is audited.
-- Special-category data (GDPR Art. 9): the API encrypts the JSON before insert, like health_questionnaire.

CREATE TABLE coaching.client_information (
    client_id           uuid PRIMARY KEY REFERENCES identity.client_profile,
    payload_encrypted   bytea NOT NULL,                 -- AES-GCM(JSON of ClientInformationDto), nonce-prefixed
    key_id              text NOT NULL,
    updated_at          timestamptz NOT NULL DEFAULT now()
);
