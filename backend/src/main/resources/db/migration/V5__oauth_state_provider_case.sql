-- V5: align oauth_states.provider / user_oauth_identities.provider check constraints
-- with JPA enum storage.
--
-- JPA @Enumerated(EnumType.STRING) writes the Java enum name (uppercase: GOOGLE, GITHUB),
-- so the DB CHECK must allow uppercase values too. We keep both casings accepted so any
-- legacy rows (lowercase from raw SQL) still validate.

ALTER TABLE oauth_states DROP CONSTRAINT ck_oauth_state_provider;

ALTER TABLE oauth_states
    ADD CONSTRAINT ck_oauth_state_provider
    CHECK (provider IN ('GOOGLE', 'GITHUB', 'google', 'github'));

ALTER TABLE user_oauth_identities DROP CONSTRAINT ck_oauth_provider_values;

ALTER TABLE user_oauth_identities
    ADD CONSTRAINT ck_oauth_provider_values
    CHECK (provider IN ('GOOGLE', 'GITHUB', 'google', 'github'));