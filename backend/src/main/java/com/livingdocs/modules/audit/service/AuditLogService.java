package com.livingdocs.modules.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.audit.model.AuditLog;
import com.livingdocs.modules.audit.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit log. All governance-relevant actions flow through
 * {@link #record} so the audit trail is always consistent.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(UUID actorUserId, String actorRole, String action,
                       String resourceType, String resourceId,
                       UUID workspaceId, Map<String, ?> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit payload, recording as empty", e);
            json = "{}";
        }
        AuditLog entry = new AuditLog(actorUserId, actorRole, action, resourceType,
                resourceId, workspaceId, json);
        repository.save(entry);
    }
}