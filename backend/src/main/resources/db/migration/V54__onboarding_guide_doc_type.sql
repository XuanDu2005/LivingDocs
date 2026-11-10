-- LivingDocs V54 — the ONBOARDING_GUIDE documentation type.
--
-- Problem this migration solves
-- ---------------------------
-- The spec asks for a library of ready-to-use default templates, and
-- "a guide for new engineers" is one of the types a documentation team
-- actually writes. Two thirds of the stack already knew about it:
--
--   * the AI service carried an ONBOARDING_GUIDE preset
--     (ai-service/app/services/generator.py) that nothing could ever
--     reach, because the backend had no such doc type — the preset was
--     dead code;
--   * the spec's template list named it.
--
-- The backend, however, had no ONBOARDING_GUIDE doc type, no default
-- template for it and no platform policy for it. A workspace that
-- wanted one had to build it by hand.
--
-- What this migration does
-- -----------------------
-- 1) Registers the ONBOARDING_GUIDE doc type in the backend enum
--    (DocType.java) — the Java side of this migration.
-- 2) Seeds the platform workflow policy for it, with the safe default
--    (MANAGER_REVIEW): an onboarding guide is prose about the system,
--    so a human reads it before it goes out.
-- 3) Seeds the global default template for it, so the template
--    resolver stops returning NULL for the type.
-- 4) Gives the seeded template the same `instructions` block the other
--    global defaults got in V48.
--
-- The system user
-- --------------
-- The global template is attributed to the system user
-- '00000000-0000-0000-0000-000000000000'. V33 created that user as
-- part of the same seed, but V33 only ever ran once: a database that
-- was seeded before that user existed — or one whose users table was
-- restored without it — has no such row, and the foreign key
-- `fk_doc_templates_creator` then rejects the insert. The statement
-- below re-creates the user the same way V33 did, so the seed works
-- on a fresh install and on an existing database alike.
-- ----------------------------------------------------------------------------

-- ----------------------------------------------------------------------------
-- 0) The system user the global template is attributed to.
--
--    The same guard V33 used: the row is created only when it is
--    missing, so the migration is idempotent and never overwrites an
--    existing account.
-- ----------------------------------------------------------------------------
INSERT INTO users (id, email, password_hash, display_name, enabled, created_at, updated_at)
SELECT '00000000-0000-0000-0000-000000000000', 'system@livingdocs.local', '', 'System', true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE id = '00000000-0000-0000-0000-000000000000');

-- ----------------------------------------------------------------------------
-- 1) The platform workflow policy.
--
--    MANAGER_REVIEW is the safe default: the type is not in the
--    AUTO_APPLY set, so a generated onboarding guide stays a draft
--    until a human submits it.
-- ----------------------------------------------------------------------------
INSERT INTO platform_doc_type_policies (id, doc_type, workflow)
VALUES (gen_random_uuid(), 'ONBOARDING_GUIDE', 'MANAGER_REVIEW')
ON CONFLICT (doc_type) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2) The global default template.
--
--    workspace_id IS NULL, so every workspace inherits it until it
--    defines its own default for the same doc type. is_default is true
--    because it is the only template of that type in the global library.
--
--    The structure mirrors the other prose types (README / MODULE_GUIDE):
--    a short summary, then the orientation sections a new engineer
--    actually needs, then the optional ones.
-- ----------------------------------------------------------------------------
INSERT INTO doc_templates (
    id, workspace_id, name, slug, description, doc_type,
    version, body, output_format,
    auto_generate_on_commit, auto_generate_on_pr, auto_generate_on_merge,
    is_default, created_by
)
SELECT
    gen_random_uuid(),
    NULL,
    'Onboarding Guide',
    'default-onboarding-guide',
    'A guide for new engineers — what the system is, how to run it, where things live.',
    'ONBOARDING_GUIDE',
    1,
    '{"version":1,"titleHint":"Onboarding Guide — {{projectName}}","instructions":"Write for an engineer who joined the team this week. Lead with what the system does and how the pieces fit together, keep the setup steps copy-pasteable, and point at concrete files and commands rather than describing them in prose. Never invent setup steps that are not in the source.","sections":[
{"id":"overview","heading":"Overview","level":2,"placeholders":[
{"key":"summary","prompt":"What does this system do, and who is it for?","required":true,"maxWords":120}]},
{"id":"architecture","heading":"Architecture","level":2,"placeholders":[
{"key":"architecture","prompt":"What are the main components and how do they fit together?","required":true}]},
{"id":"setup","heading":"Setup","level":2,"placeholders":[
{"key":"setup","prompt":"How does a new engineer get the project running locally?","required":true}]},
{"id":"codebase","heading":"Where things live","level":2,"placeholders":[
{"key":"structure","prompt":"Which directories and modules does a newcomer need to know about?","required":false,"binding":"code.class"}]},
{"id":"workflows","heading":"Common workflows","level":2,"placeholders":[
{"key":"workflows","prompt":"Which day-to-day tasks does a newcomer perform, and how?","required":false}]}
],"variables":[
{"key":"projectName","default":"LivingDocs","description":"Project name."}
]}'::jsonb,
    'MARKDOWN',
    false,
    false,
    false,
    true,
    '00000000-0000-0000-0000-000000000000'
WHERE NOT EXISTS (
    SELECT 1 FROM doc_templates d
    WHERE d.slug = 'default-onboarding-guide' AND d.version = 1
);

-- ----------------------------------------------------------------------------
-- 3) The `instructions` block.
--
--    V48 backfilled the global defaults, but only for the templates that
--    existed at that time. The ONBOARDING_GUIDE template is inserted
--    above with its instructions already in place, so this UPDATE only
--    has to cover the case where an operator had already created a
--    template with this slug before the migration ran — in which case the
--    body is left as the operator wrote it.
--
--    The WHERE clause makes it a no-op for the row we just inserted.
-- ----------------------------------------------------------------------------
UPDATE doc_templates
SET body = jsonb_set(
        body,
        '{instructions}',
        to_jsonb('Write for an engineer who joined the team this week. Lead with what the system does and how the pieces fit together, keep the setup steps copy-pasteable, and point at concrete files and commands rather than describing them in prose. Never invent setup steps that are not in the source.'::text),
        true
    )
WHERE workspace_id IS NULL
  AND doc_type = 'ONBOARDING_GUIDE'
  AND body ? 'sections'
  AND NOT (body ? 'instructions');
