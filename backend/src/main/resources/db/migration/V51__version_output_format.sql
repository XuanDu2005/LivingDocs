-- ----------------------------------------------------------------------------
-- P2-9: make the template's output_format a live setting.
--
-- Until now a template could say "render me as HTML" and nothing
-- happened — the column was written and never read. The AI always
-- produced markdown, and the version stored exactly that.
--
-- The version keeps BOTH representations:
--
--   * body_markdown  — the canonical markdown. Everything that reasons
--                      about the structure of a document reads this
--                      column: the review workflow anchors a comment to
--                      a markdown heading, the template compliance
--                      check counts the markdown headings, and the diff
--                      view compares markdown. Replacing it with HTML
--                      would silently break all three.
--
--   * rendered_body  — the same content converted into the format the
--                      template asked for (currently MARKDOWN or HTML).
--                      This is what a consumer that wants the document
--                      in its published shape reads — for example the
--                      commit to a repository.
--
--   * output_format  — the format the version was rendered into. It is
--                      stored on the version rather than looked up
--                      because the template is versioned: a template can
--                      be edited later, and the version has to keep
--                      the shape it was generated with.
--
-- PDF is accepted by the API but is not implemented yet — the renderer
-- falls back to markdown and logs a warning.
-- ----------------------------------------------------------------------------
ALTER TABLE document_versions
    ADD COLUMN IF NOT EXISTS output_format VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN';

ALTER TABLE document_versions
    ADD COLUMN IF NOT EXISTS rendered_body TEXT;

-- The existing rows were all markdown.
UPDATE document_versions
   SET rendered_body = body_markdown
 WHERE rendered_body IS NULL;

-- The check constraint mirrors the one on doc_templates (V9).
ALTER TABLE document_versions
    DROP CONSTRAINT IF EXISTS chk_document_versions_output_format;

ALTER TABLE document_versions
    ADD CONSTRAINT chk_document_versions_output_format
    CHECK (output_format IN ('MARKDOWN', 'HTML', 'PDF'));
