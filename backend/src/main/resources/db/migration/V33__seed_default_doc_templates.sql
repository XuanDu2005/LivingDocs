-- Seed the global default documentation templates.
--
-- LivingDocs ships with a library of ready-to-use templates (API
-- reference, README, module guide, ADR, ...). Without these rows the
-- template resolver returns NULL for every doc type, so a freshly
-- created workspace has no default template and the AI has no
-- structure to follow.
--
-- The templates are global (workspace_id IS NULL) so every workspace
-- inherits them until it defines its own default for the same doc type.
-- created_by points at the first administrator so the foreign key holds
-- on a fresh install that already has the seed admin user.

INSERT INTO doc_templates (
    id, workspace_id, name, slug, description, doc_type,
    version, body, output_format,
    auto_generate_on_commit, auto_generate_on_pr, auto_generate_on_merge,
    is_default, created_by
)
SELECT
    gen_random_uuid(),
    NULL,
    t.name,
    t.slug,
    t.description,
    t.doc_type,
    1,
    t.body::jsonb,
    'MARKDOWN',
    false,
    false,
    false,
    true,
    (SELECT id FROM users ORDER BY created_at ASC LIMIT 1)
FROM (VALUES
    (
        'API Reference',
        'default-api-reference',
        'Endpoint-by-endpoint reference with parameters, responses and errors.',
        'API_REFERENCE',
        '{"version":1,"titleHint":"API Reference — {{moduleName}}","sections":[
            {"id":"overview","heading":"Overview","level":2,"placeholders":[
                {"key":"summary","prompt":"What does this API cover?","required":true,"maxWords":80}]},
            {"id":"authentication","heading":"Authentication","level":2,"placeholders":[
                {"key":"auth","prompt":"How are requests authenticated?","required":false}]},
            {"id":"endpoints","heading":"Endpoints","level":2,"placeholders":[
                {"key":"endpoints","prompt":"List every public endpoint with method, path, parameters and response.","required":true,"binding":"code.route"},
                {"key":"errors","prompt":"List the error codes this API can return.","required":false}]},
            {"id":"usage","heading":"Usage example","level":2,"placeholders":[
                {"key":"example","prompt":"A minimal working example for the most common call.","required":false}]}
        ],"variables":[
            {"key":"moduleName","default":"core","description":"Module under documentation."}
        ]}'
    ),
    (
        'README',
        'default-readme',
        'Project README with quickstart, installation and contributing.',
        'README',
        '{"version":1,"titleHint":"{{projectName}}","sections":[
            {"id":"introduction","heading":"Introduction","level":2,"placeholders":[
                {"key":"summary","prompt":"What does this project do and who is it for?","required":true,"maxWords":120}]},
            {"id":"installation","heading":"Installation","level":2,"placeholders":[
                {"key":"install","prompt":"How do I install and run this project?","required":true}]},
            {"id":"usage","heading":"Usage","level":2,"placeholders":[
                {"key":"usage","prompt":"Show the most common usage of the project.","required":true}]},
            {"id":"contributing","heading":"Contributing","level":2,"placeholders":[
                {"key":"contributing","prompt":"How do contributors set up and submit changes?","required":false}]}
        ],"variables":[
            {"key":"projectName","default":"LivingDocs","description":"Project name."}
        ]}'
    ),
    (
        'Module Guide',
        'default-module-guide',
        'Walkthrough of a single module or package.',
        'MODULE_GUIDE',
        '{"version":1,"titleHint":"Module Guide — {{moduleName}}","sections":[
            {"id":"overview","heading":"Overview","level":2,"placeholders":[
                {"key":"summary","prompt":"One paragraph summary of what this module does.","required":true,"maxWords":80}]},
            {"id":"api-surface","heading":"API surface","level":2,"placeholders":[
                {"key":"endpoints","prompt":"List the public endpoints exposed by this module.","required":false,"binding":"code.route"}]},
            {"id":"structure","heading":"Structure","level":2,"placeholders":[
                {"key":"structure","prompt":"Describe the important classes and how they relate.","required":false,"binding":"code.class"}]},
            {"id":"dependencies","heading":"Dependencies","level":2,"placeholders":[
                {"key":"dependencies","prompt":"What does this module depend on?","required":false}]}
        ],"variables":[
            {"key":"moduleName","default":"core","description":"Module under documentation."}
        ]}'
    ),
    (
        'Architecture Decision Record',
        'default-adr',
        'ADR capturing context, decision and consequences.',
        'ADR',
        '{"version":1,"titleHint":"ADR — {{decisionTitle}}","sections":[
            {"id":"context","heading":"Context","level":2,"placeholders":[
                {"key":"context","prompt":"What problem are we solving? What forces are at play?","required":true}]},
            {"id":"decision","heading":"Decision","level":2,"placeholders":[
                {"key":"decision","prompt":"What was decided?","required":true}]},
            {"id":"consequences","heading":"Consequences","level":2,"placeholders":[
                {"key":"consequences","prompt":"What are the positive and negative consequences?","required":true}]}
        ],"variables":[
            {"key":"decisionTitle","default":"Decision","description":"Short title of the decision."}
        ]}'
    ),
    (
        'Architecture Overview',
        'default-architecture',
        'System architecture overview — components, data flow and boundaries.',
        'ARCHITECTURE',
        '{"version":1,"titleHint":"Architecture — {{systemName}}","sections":[
            {"id":"overview","heading":"Overview","level":2,"placeholders":[
                {"key":"summary","prompt":"What is the shape of this system?","required":true,"maxWords":120}]},
            {"id":"components","heading":"Components","level":2,"placeholders":[
                {"key":"components","prompt":"Describe the main components and their responsibilities.","required":true}]},
            {"id":"data-flow","heading":"Data flow","level":2,"placeholders":[
                {"key":"dataFlow","prompt":"How does data move through the system?","required":false}]},
            {"id":"boundaries","heading":"Boundaries","level":2,"placeholders":[
                {"key":"boundaries","prompt":"Where are the trust boundaries and external dependencies?","required":false}]}
        ],"variables":[
            {"key":"systemName","default":"LivingDocs","description":"System under documentation."}
        ]}'
    )
) AS t(name, slug, description, doc_type, body)
WHERE NOT EXISTS (
    SELECT 1 FROM doc_templates d
    WHERE d.slug = t.slug AND d.version = 1
);
