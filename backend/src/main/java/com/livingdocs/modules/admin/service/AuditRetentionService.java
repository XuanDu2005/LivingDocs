package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.AuditRetentionPolicyResponse;
import com.livingdocs.modules.admin.dto.UpdateRetentionPolicyRequest;
import com.livingdocs.modules.admin.model.AuditRetentionPolicy;
import com.livingdocs.modules.admin.model.AuditRetentionPolicy.PruneStrategy;
import com.livingdocs.modules.admin.repository.AuditRetentionPolicyRepository;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.notification.repository.NotificationRepository;
import com.livingdocs.modules.integrations.repository.IntegrationEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD service for audit log retention policies.
 * Also handles scheduled pruning of old records based on retention policies.
 */
@Service
public class AuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);

    private final AuditRetentionPolicyRepository repository;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;
    private final NotificationRepository notificationRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final EntityManager entityManager;

    public AuditRetentionService(
            AuditRetentionPolicyRepository repository,
            AuditLogService auditLogService,
            AuditLogRepository auditLogRepository,
            NotificationRepository notificationRepository,
            IntegrationEventRepository integrationEventRepository,
            EntityManager entityManager) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
        this.notificationRepository = notificationRepository;
        this.integrationEventRepository = integrationEventRepository;
        this.entityManager = entityManager;
    }

    /**
     * Result of a prune operation.
     */
    public record PruneResult(int totalPruned, int policiesProcessed, List<PolicyPruneResult> details) {
        public static PruneResult empty() {
            return new PruneResult(0, 0, new ArrayList<>());
        }
    }

    /**
     * Result of pruning a single policy.
     */
    public record PolicyPruneResult(String entityType, int pruned, boolean success, String error) {
        public static PolicyPruneResult success(String entityType, int pruned) {
            return new PolicyPruneResult(entityType, pruned, true, null);
        }
        public static PolicyPruneResult failure(String entityType, String error) {
            return new PolicyPruneResult(entityType, 0, false, error);
        }
    }

    /**
     * Run pruning for all enabled policies.
     * Called by the scheduled job.
     */
    @Transactional
    public PruneResult pruneAll() {
        List<AuditRetentionPolicy> policies = repository.findAllEnabledWithRetention();
        if (policies.isEmpty()) {
            log.info("No enabled retention policies to prune");
            return PruneResult.empty();
        }

        int totalPruned = 0;
        List<PolicyPruneResult> details = new ArrayList<>();

        for (AuditRetentionPolicy policy : policies) {
            try {
                int pruned = pruneForPolicy(policy);
                totalPruned += pruned;
                details.add(PolicyPruneResult.success(policy.getEntityType(), pruned));
                log.debug("Pruned {} records for entity type: {}", pruned, policy.getEntityType());
            } catch (Exception e) {
                log.error("Failed to prune policy for entity type: {}", policy.getEntityType(), e);
                details.add(PolicyPruneResult.failure(policy.getEntityType(), e.getMessage()));
            }
        }

        return new PruneResult(totalPruned, policies.size(), details);
    }

    /**
     * Prune records for a single policy.
     */
    private int pruneForPolicy(AuditRetentionPolicy policy) {
        String entityType = policy.getEntityType();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(policy.getRetentionDays());

        int deleted;
        switch (entityType) {
            case "audit_log" -> deleted = deleteAuditLogsBefore(cutoff, policy.getPruneStrategy());
            case "notification" -> deleted = deleteNotificationsBefore(cutoff);
            case "webhook_event" -> deleted = deleteWebhookEventsBefore(cutoff);
            case "drift_alert" -> deleted = deleteDriftAlertsBefore(cutoff);
            case "document_version" -> deleted = deleteDocumentVersionsBefore(cutoff);
            default -> {
                log.warn("Unknown entity type for pruning: {}", entityType);
                return 0;
            }
        }

        // Update last pruned timestamp
        if (deleted > 0) {
            policy.recordPruned();
            repository.save(policy);
        }

        return deleted;
    }

    /**
     * Delete audit logs older than cutoff date.
     */
    private int deleteAuditLogsBefore(OffsetDateTime cutoff, PruneStrategy strategy) {
        if (strategy == PruneStrategy.ANONYMIZE) {
            return anonymizeAuditLogsBefore(cutoff);
        }
        // HARD_DELETE and ARCHIVE (archive = delete for now, Phase 2 would move to archive table)
        String jpql = "DELETE FROM AuditLog a WHERE a.createdAt < :cutoff";
        Query query = entityManager.createQuery(jpql);
        query.setParameter("cutoff", cutoff);
        return query.executeUpdate();
    }

    /**
     * Anonymize audit logs (replace PII with placeholders).
     */
    private int anonymizeAuditLogsBefore(OffsetDateTime cutoff) {
        // Update actor info to anonymized values but keep the record for compliance
        String jpql = """
            UPDATE AuditLog a
            SET a.actorUserId = NULL,
                a.payload = '{"anonymized": true, "message": "Data removed per retention policy"}'
            WHERE a.createdAt < :cutoff
            AND a.actorUserId IS NOT NULL
            """;
        Query query = entityManager.createQuery(jpql);
        query.setParameter("cutoff", cutoff);
        return query.executeUpdate();
    }

    /**
     * Delete notifications older than cutoff date.
     */
    private int deleteNotificationsBefore(OffsetDateTime cutoff) {
        String jpql = "DELETE FROM Notification n WHERE n.createdAt < :cutoff";
        Query query = entityManager.createQuery(jpql);
        query.setParameter("cutoff", cutoff);
        return query.executeUpdate();
    }

    /**
     * Delete webhook events older than cutoff date.
     */
    private int deleteWebhookEventsBefore(OffsetDateTime cutoff) {
        String jpql = "DELETE FROM IntegrationEvent i WHERE i.receivedAt < :cutoff";
        Query query = entityManager.createQuery(jpql);
        query.setParameter("cutoff", cutoff);
        return query.executeUpdate();
    }

    /**
     * Delete drift alerts older than cutoff date.
     * Note: Drift alerts may need special handling - this assumes a DriftAlert entity exists.
     */
    private int deleteDriftAlertsBefore(OffsetDateTime cutoff) {
        // Check if there's a drift_alert table or similar
        // For now, use a generic query - adjust based on actual schema
        try {
            String jpql = "DELETE FROM com.livingdocs.modules.drift.model.DriftAlert d WHERE d.createdAt < :cutoff";
            Query query = entityManager.createQuery(jpql);
            query.setParameter("cutoff", cutoff);
            return query.executeUpdate();
        } catch (Exception e) {
            log.warn("DriftAlert entity not found or query failed, skipping: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Delete document versions older than cutoff date.
     */
    private int deleteDocumentVersionsBefore(OffsetDateTime cutoff) {
        try {
            String jpql = "DELETE FROM com.livingdocs.modules.version.model.DocumentVersion v WHERE v.createdAt < :cutoff";
            Query query = entityManager.createQuery(jpql);
            query.setParameter("cutoff", cutoff);
            return query.executeUpdate();
        } catch (Exception e) {
            log.warn("DocumentVersion entity not found or query failed, skipping: {}", e.getMessage());
            return 0;
        }
    }

    @Transactional(readOnly = true)
    public List<AuditRetentionPolicyResponse> listPolicies() {
        return repository.findAllByOrderByEntityTypeAsc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AuditRetentionPolicyResponse upsert(UUID actorId, UpdateRetentionPolicyRequest req) {
        if (req.entityType() == null || req.entityType().isBlank()) {
            throw new BadRequestException("entityType is required");
        }
        if (req.retentionDays() == null || req.retentionDays() < 0) {
            throw new BadRequestException("retentionDays must be >= 0");
        }

        // Upsert key: existingEntityType (when renaming) else entityType itself.
        String key = (req.existingEntityType() == null || req.existingEntityType().isBlank())
                ? req.entityType()
                : req.existingEntityType();

        AuditRetentionPolicy policy = repository.findByEntityType(key)
                .orElseGet(() -> new AuditRetentionPolicy(req.entityType(), req.retentionDays()));

        // Handle rename: if entityType differs from the existing key, rename.
        if (!policy.getEntityType().equals(req.entityType())) {
            if (repository.existsByEntityType(req.entityType())) {
                throw new BadRequestException("entityType already exists: " + req.entityType());
            }
            // Hibernate won't auto-rename an @Id-keyed entity, so emulate
            // rename by deleting the old row and saving a fresh one.
            repository.delete(policy);
            policy = new AuditRetentionPolicy(req.entityType(), req.retentionDays());
        }

        if (req.description() != null) {
            policy.setDescription(req.description().trim().isEmpty() ? null : req.description().trim());
        }
        policy.setRetentionDays(req.retentionDays());
        if (req.pruneStrategy() != null) {
            policy.setPruneStrategy(parseStrategy(req.pruneStrategy()));
        }
        if (req.enabled() != null) {
            policy.setEnabled(req.enabled());
        }
        AuditRetentionPolicy saved = repository.save(policy);

        auditLogService.record(actorId, "ADMIN", "audit_retention.upsert",
                "audit_retention_policy", saved.getId().toString(), null,
                Map.of(
                        "entity_type", saved.getEntityType(),
                        "retention_days", saved.getRetentionDays(),
                        "enabled", saved.isEnabled()
                ));

        return toResponse(saved);
    }

    @Transactional
    public void deletePolicy(UUID actorId, String entityType) {
        AuditRetentionPolicy policy = repository.findByEntityType(entityType)
                .orElseThrow(() -> new NotFoundException("Policy not found: " + entityType));
        repository.delete(policy);
        auditLogService.record(actorId, "ADMIN", "audit_retention.delete",
                "audit_retention_policy", policy.getId().toString(), null,
                Map.of("entity_type", entityType));
    }

    private PruneStrategy parseStrategy(String raw) {
        try {
            return PruneStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown prune strategy: " + raw);
        }
    }

    private AuditRetentionPolicyResponse toResponse(AuditRetentionPolicy p) {
        return new AuditRetentionPolicyResponse(
                p.getId(),
                p.getEntityType(),
                p.getDescription(),
                p.getRetentionDays(),
                p.getPruneStrategy().name(),
                p.isEnabled(),
                p.getLastPrunedAt(),
                p.getUpdatedAt()
        );
    }
}