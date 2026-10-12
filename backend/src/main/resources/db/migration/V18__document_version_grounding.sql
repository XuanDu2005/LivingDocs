-- LivingDocs V18 — Store grounding evidence for AI-generated document versions.
--
-- Phase 1 follow-up: persist the GroundingSource list returned by the AI
-- service so the frontend can fetch it independently from version creation.
-- Idempotent: uses DO blocks to skip if already applied.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_name = 'document_versions' AND column_name = 'grounding_json') THEN
    ALTER TABLE document_versions ADD COLUMN grounding_json JSONB NULL;
  END IF;
END $$;
