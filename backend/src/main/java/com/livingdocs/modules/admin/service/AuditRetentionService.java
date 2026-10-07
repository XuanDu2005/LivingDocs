package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.AuditRetentionPolicyResponse;
import com.livingdocs.modules.admin.dto.UpdateRetentionPolicyRequest;
import com.livingdocs.modules.admin.model.AuditRetentionPolicy;
import com.livingdocs.modules.admin.model.AuditRetentionPolicy.PruneStrategy;
import com.livingdocs.modules.admin.repository.AuditRetentionPolicyRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD service for audit log retention policies. Phase 1 exposes only
 * the configuration surface — a scheduled pruner is wired in Phase 4.
 */
@Service
public class AuditRetentionService {

    private final AuditRetentionPolicyRepository repository;
    private final AuditLogService auditLogService;

    public AuditRetentionService(AuditRetentionPolicyRepository repository,
                                 AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
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