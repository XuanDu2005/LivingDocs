package com.livingdocs.modules.audit.repository;

import com.livingdocs.modules.audit.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findAllByWorkspaceIdOrderByCreatedAtDesc(UUID workspaceId);

    List<AuditLog> findAllByActorUserIdOrderByCreatedAtDesc(UUID actorUserId);

    List<AuditLog> findAllByActionOrderByCreatedAtDesc(String action);
}