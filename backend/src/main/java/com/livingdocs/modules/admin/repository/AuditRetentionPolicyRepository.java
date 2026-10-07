package com.livingdocs.modules.admin.repository;

import com.livingdocs.modules.admin.model.AuditRetentionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditRetentionPolicyRepository extends JpaRepository<AuditRetentionPolicy, UUID> {

    Optional<AuditRetentionPolicy> findByEntityType(String entityType);

    List<AuditRetentionPolicy> findAllByOrderByEntityTypeAsc();

    boolean existsByEntityType(String entityType);
}