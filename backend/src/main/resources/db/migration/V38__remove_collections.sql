-- LivingDocs V38 — remove the document collections feature.
--
-- Why this migration exists
-- -----------------------
-- Collections (curated folders of documents) turned out to be redundant:
-- the workspace already groups documents by repository, by module and by
-- tag, and the knowledge base covers the "browse by topic" axis. Keeping
-- a third, parallel taxonomy meant every workspace had to be curated
-- twice and every document had to be filed by hand.
--
-- The feature was introduced in V37 (tables + permission codes + grants)
-- and had not been released, so this migration rewrites the world rather
-- than reversing it: the tables are dropped and the permission codes are
-- removed from the catalogue.
--
-- What this migration does
-- -----------------------
-- 1) Drops the collection_documents link table and the
--    document_collections table.
-- 2) Removes the COLLECTION_VIEW / COLLECTION_MANAGE rows from
--    `permissions` (the role_permissions rows go with them via the
--    foreign key).
-- 3) Deletes the now-empty 'collection' permission category rows, if any.
--
-- The application change that goes with it is the removal of
-- CollectionController / CollectionService, the CollectionsPage, the
-- `collections` API client and the navigation entries.

-- ----------------------------------------------------------------------------
-- 1) Drop the tables.
--
--    collection_documents references document_collections, so it has to
--    go first. IF EXISTS keeps the migration re-runnable.
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS collection_documents;
DROP TABLE IF EXISTS document_collections;

-- ----------------------------------------------------------------------------
-- 2) Remove the permission codes.
--
--    role_permissions rows are removed by the ON DELETE CASCADE of the
--    permission_id foreign key; the explicit DELETE below is only a
--    safety net for deployments where the cascade was not in place.
-- ----------------------------------------------------------------------------
DELETE FROM role_permissions WHERE permission_id IN (
    SELECT id FROM permissions WHERE code IN ('COLLECTION_VIEW', 'COLLECTION_MANAGE')
);
DELETE FROM permissions WHERE code IN ('COLLECTION_VIEW', 'COLLECTION_MANAGE');
