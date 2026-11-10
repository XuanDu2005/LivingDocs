-- Add the optional `instructions` field to the seeded default templates.
--
-- `instructions` holds the free-form formatting rules that apply to the
-- whole document (tone, table style, language, ...). The AI service
-- renders it verbatim into the system prompt, next to the per-section
-- structure. It is optional in the canonical schema — the normalisation
-- in TemplateSchema defaults it to an empty string — so this migration
-- only backfills the global defaults with something useful.

UPDATE doc_templates
SET body = jsonb_set(
        body,
        '{instructions}',
        to_jsonb(
            CASE doc_type
                WHEN 'API_REFERENCE' THEN
                    'Use a strict API reference layout. Present every endpoint as a heading with its method and path, followed by a parameters table, the response shape, the error codes and a minimal working example. Never describe an endpoint that is not present in the source.'
                WHEN 'README' THEN
                    'Write for someone cloning the repository for the first time. Lead with what the project does and who it is for, keep the quickstart copy-pasteable, and put configuration in a table. Do not invent installation steps that are not in the source.'
                WHEN 'MODULE_GUIDE' THEN
                    'Describe the module as a whole first, then walk through each public class or function in its own subsection. Ground every statement in the code.'
                WHEN 'ADR' THEN
                    'Use the Context / Decision / Consequences / Alternatives structure. Keep the decision statement to a single actionable sentence and list the alternatives that were rejected.'
                WHEN 'ARCHITECTURE' THEN
                    'Describe the components, their responsibilities, the data flow between them and the trust boundaries. Group the content by component and state the direction of every data flow.'
                ELSE
                    ''
            END
        ),
        true
    )
WHERE workspace_id IS NULL
  AND body ? 'sections'
  AND NOT (body ? 'instructions');
