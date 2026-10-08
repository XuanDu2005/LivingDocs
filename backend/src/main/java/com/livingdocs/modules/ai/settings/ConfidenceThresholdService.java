package com.livingdocs.modules.ai.settings;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.workspace.model.WorkspaceSettings;
import com.livingdocs.modules.workspace.repository.WorkspaceSettingsRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Read / write the workspace-level AI confidence threshold.
 *
 * <p>The {@code ai_confidence_threshold} column already exists on
 * {@code workspace_settings}; this service is dedicated so the
 * PUT endpoint can be added without widening
 * {@link AiSettingsService}. The threshold is used by
 * {@code AiDocumentationService} when deciding whether to auto-approve
 * a generated version.
 */
@Service
public class ConfidenceThresholdService {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceThresholdService.class);

    private final WorkspaceSettingsRepository settingsRepository;
    private final WorkspaceService workspaceService;
    private final AuditLogService auditLogService;

    public ConfidenceThresholdService(WorkspaceSettingsRepository settingsRepository,
                                      WorkspaceService workspaceService,
                                      AuditLogService auditLogService) {
        this.settingsRepository = settingsRepository;
        this.workspaceService = workspaceService;
        this.auditLogService = auditLogService;
    }

    /**
     * Read the threshold, lazily creating a default row when the
     * workspace has none configured yet.
     */
    @Transactional
    public float get(UUID workspaceId) {
        WorkspaceSettings s = loadOrCreate(workspaceId);
        return s.getAiConfidenceThreshold();
    }

    /**
     * Update the threshold. Requires MANAGER membership.
     */
    @Transactional
    public float update(UUID actorId, UUID workspaceId, float value) {
        if (value < 0.0f || value > 1.0f) {
            throw new com.livingdocs.common.exception.BadRequestException(
                    "Confidence threshold must be between 0.0 and 1.0");
        }
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);

        WorkspaceSettings s = loadOrCreate(workspaceId);
        float previous = s.getAiConfidenceThreshold();
        s.setAiConfidenceThreshold(value);
        settingsRepository.save(s);

        auditLogService.record(actorId, "MANAGER",
                "workspace.ai.threshold.update",
                "workspace", workspaceId.toString(), workspaceId,
                Map.of("previous", previous, "value", value));

        log.info("Workspace {} AI confidence threshold: {} -> {}", workspaceId, previous, value);
        return value;
    }

    private WorkspaceSettings loadOrCreate(UUID workspaceId) {
        return settingsRepository.findById(workspaceId)
                .orElseGet(() -> settingsRepository.save(new WorkspaceSettings(workspaceId)));
    }

    /** Used by other services that don't go through the controller. */
    @Transactional(readOnly = true)
    public float resolve(UUID workspaceId) {
        return settingsRepository.findById(workspaceId)
                .map(WorkspaceSettings::getAiConfidenceThreshold)
                .orElse(0.70f);
    }

    /** Sanity-check guard for places that need an explicit value. */
    @Transactional(readOnly = true)
    public WorkspaceSettings requireSettings(UUID workspaceId) {
        return settingsRepository.findById(workspaceId)
                .orElseThrow(() -> new NotFoundException("Workspace settings not found"));
    }
}