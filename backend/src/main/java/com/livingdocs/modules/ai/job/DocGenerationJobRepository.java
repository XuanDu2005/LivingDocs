package com.livingdocs.modules.ai.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocGenerationJobRepository extends JpaRepository<DocGenerationJob, UUID> {

    /** Look up a job by the correlation id shared by every event in the flow. */
    Optional<DocGenerationJob> findByCorrelationId(UUID correlationId);

    /** The most recent job for a document — used by the "generation status" endpoint. */
    Optional<DocGenerationJob> findFirstByDocumentIdOrderByCreatedAtDesc(UUID documentId);

    /** Every job for a document, newest first — used by the UI activity list. */
    List<DocGenerationJob> findAllByDocumentIdOrderByCreatedAtDesc(UUID documentId);
}