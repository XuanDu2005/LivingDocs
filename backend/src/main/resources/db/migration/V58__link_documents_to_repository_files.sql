-- LivingDocs V58 — link the documentation side and the repository side.
--
-- Problem this migration solves
-- ---------------------------
-- LivingDocs had two completely separate review workflows that never
-- touched each other:
--
--   1. The *document* workflow: a Document owns DocumentVersions, which
--      go through the two-tier Staff → Manager review and end up as the
--      published head of the document.
--
--   2. The *doc update* workflow: a DocUpdateRequest owns a proposed
--      body for a file inside a GitHub repository, gets approved by any
--      workspace member, and is pushed as a pull request.
--
-- Nothing connected them:
--
--   * `documents` had no column saying which file of which repository
--     the document corresponds to. The commit dialog had to *guess* the
--     path (`docs/{slug}.md`) on the frontend.
--
--   * `doc_update_requests.document_id` existed but was always NULL —
--     both call sites passed a hard-coded `null`. So a proposal about
--     `docs/api/auth.md` could never be found from the document, and the
--     document could never be found from the proposal.
--
--   * A proposal that was approved never became a DocumentVersion, so
--     it never appeared in the shared review queue and never left an
--     audit trail.
--
-- What this migration does
-- -----------------------
-- 1) Adds `documents.file_path` — the path of the file inside the
--    repository that this document is published to. Nullable, because a
--    document that is not bound to a repository has no path.
--
-- 2) Adds `doc_update_requests.document_version_id` — the version this
--    proposal materialised into, so the proposal and the version can be
--    followed in both directions.
--
-- 3. Leaves `documents.file_path` empty.
--
--    An earlier draft of this migration backfilled the path with
--    `docs/{slug}.md`. That was wrong: the repository layout is not
--    derivable from the slug. The per-entity documents are named after
--    the entity (`api-client.md` in the repository, `apiclient` as the
--    slug) and the overview documents are named after their doc type
--    (`architecture.md` for `ARCHITECTURE`). A guessed path would make
--    the commit dialog write a *second* file next to the real one.
--
--    The path is instead discovered from the repository itself: the
--    resolver (DocumentFileResolver) matches the file stem against the
--    document's slug, doc type and title, and the path it finds is
--    written back onto the document. A path a user set by hand is
--    never overwritten.
--
-- The WHERE clause on the old backfill is kept in the comment below
-- for reference, not executed:
--
--   WHERE repository_id IS NOT NULL
--     AND file_path IS NULL
--     AND slug NOT LIKE 'repo-%-%';

-- ----------------------------------------------------------------------------
-- 1. The file a document is published to.
-- ----------------------------------------------------------------------------
ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS file_path VARCHAR(512);

-- A document may only name a file inside a repository, so the path is
-- only meaningful together with repository_id. This index serves the
-- lookup that resolves a scanned repository file to its document.
CREATE INDEX IF NOT EXISTS idx_documents_repository_file_path
    ON documents (repository_id, file_path);

-- ----------------------------------------------------------------------------
-- 2. The version a doc update proposal materialised into.
-- ----------------------------------------------------------------------------
ALTER TABLE doc_update_requests
    ADD COLUMN IF NOT EXISTS document_version_id UUID
        REFERENCES document_versions(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_doc_update_requests_version
    ON doc_update_requests (document_version_id);

-- ----------------------------------------------------------------------------
-- 3. Backfill the file path of the documents that already exist.
-- ----------------------------------------------------------------------------
-- (intentionally empty — see the note above)
