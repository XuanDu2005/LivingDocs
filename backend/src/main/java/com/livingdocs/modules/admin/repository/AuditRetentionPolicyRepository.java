package com.livingdocs.modules.admin.repository;

import com.livingdocs.modules.admin.model.AuditRetentionPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditRetentionPolicyRepository extends JpaRepository<AuditRetentionPolicy, UUID> {

    Optional<AuditRetentionPolicy> findByEntityType(String entityType);

    List<AuditRetentionPolicy> findAllByOrderByEntityTypeAsc();

    boolean existsByEntityType(String entityType);

    @Query("SELECT p FROM AuditRetentionPolicy p WHERE p.enabled = true AND p.retentionDays > 0")
    List<AuditRetentionPolicy> findAllEnabledWithRetention();
}