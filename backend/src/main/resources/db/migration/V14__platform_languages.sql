-- LivingDocs V14 — Platform-wide supported programming languages.
--
-- Phase 5: administrators can declare the default set of programming
-- languages that every workspace starts with. Individual workspaces
-- can still override the default per-language custom prompt or even
-- disable a language entirely.

CREATE TABLE platform_languages (
    id              UUID         PRIMARY KEY,
    language_code   VARCHAR(40)  NOT NULL,
    language_name   VARCHAR(80)  NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    default_prompt  TEXT         NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_platform_languages UNIQUE (language_code)
);

CREATE INDEX idx_platform_languages_sort ON platform_languages (sort_order, language_code);

-- Seed with the same 15 languages that the frontend pre-defined list
-- already advertises. Workspace overrides added afterwards can be done
-- via the per-workspace PUT endpoint and are independent of this list.
INSERT INTO platform_languages (id, language_code, language_name, enabled, default_prompt, sort_order)
VALUES
    (gen_random_uuid(), 'javascript', 'JavaScript',  TRUE, NULL, 10),
    (gen_random_uuid(), 'typescript', 'TypeScript',  TRUE, NULL, 20),
    (gen_random_uuid(), 'python',     'Python',      TRUE, NULL, 30),
    (gen_random_uuid(), 'java',       'Java',        TRUE, NULL, 40),
    (gen_random_uuid(), 'go',         'Go',          TRUE, NULL, 50),
    (gen_random_uuid(), 'rust',       'Rust',        TRUE, NULL, 60),
    (gen_random_uuid(), 'cpp',        'C++',         TRUE, NULL, 70),
    (gen_random_uuid(), 'csharp',     'C#',          TRUE, NULL, 80),
    (gen_random_uuid(), 'ruby',       'Ruby',        TRUE, NULL, 90),
    (gen_random_uuid(), 'php',        'PHP',         TRUE, NULL, 100),
    (gen_random_uuid(), 'swift',      'Swift',       TRUE, NULL, 110),
    (gen_random_uuid(), 'kotlin',     'Kotlin',      TRUE, NULL, 120),
    (gen_random_uuid(), 'scala',      'Scala',       TRUE, NULL, 130),
    (gen_random_uuid(), 'sql',        'SQL',         TRUE, NULL, 140),
    (gen_random_uuid(), 'shell',      'Shell/Bash',  TRUE, NULL, 150);
