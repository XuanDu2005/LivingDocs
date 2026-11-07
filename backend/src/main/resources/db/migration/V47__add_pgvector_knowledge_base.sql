-- 1. Enable the pgvector extension in PostgreSQL
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. Create the knowledge_chunks table with an embedding column of type vector
CREATE TABLE knowledge_chunks (
    id VARCHAR(255) PRIMARY KEY,
    workspace_id UUID,
    document_id UUID,
    content TEXT NOT NULL,
    -- Vector column with 1536 dimensions (compatible with text-embedding-3-small)
    embedding vector(1536), 
    metadata JSONB
);

-- 3. Create an index to improve search performance (HNSW)
CREATE INDEX ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);