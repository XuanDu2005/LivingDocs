package com.livingdocs.modules.audit.repository;

import com.livingdocs.modules.audit.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    Page<AuditLog> findAllByWorkspaceId(UUID workspaceId, Pageable pageable);

    /**
     * The audit trail of a single document, newest first.
     *
     * <p>This is what answers "the complete audit trail of a document"
     * for the Manager tier: the AI generation, the auto-update, the
     * human edits, the Staff verdicts and the Manager decisions —
     * without the unrelated actions of the rest of the workspace.
     *
     * <p>Backed by {@code idx_audit_logs_document}, which was added
     * together with the {@code document_id} column in V42.
     */
    Page<AuditLog> findAllByDocumentId(UUID documentId, Pageable pageable);

    /**
     * The number of entries in the trail of a document. Used to tell the
     * reader how much of the trail they are looking at.
     */
    long countByDocumentId(UUID documentId);

    List<AuditLog> findAllByActorUserIdOrderByCreatedAtDesc(UUID actorUserId);

    List<AuditLog> findAllByActionOrderByCreatedAtDesc(String action);

    List<AuditLog> findAllByActionStartingWithOrderByCreatedAtDesc(String actionPrefix);
}
