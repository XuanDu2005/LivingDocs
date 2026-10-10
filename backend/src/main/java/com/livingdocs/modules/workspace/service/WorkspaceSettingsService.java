package com.livingdocs.modules.workspace.service;

import com.livingdocs.modules.workspace.dto.UpdateWorkspaceSettingsRequest;
import com.livingdocs.modules.workspace.dto.WorkspaceSettingsResponse;
import com.livingdocs.modules.workspace.model.WorkspaceSettings;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class WorkspaceSettingsService {

    private static final Set<String> VALID_DRIFT_THRESHOLDS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> VALID_MERGE_POLICIES = Set.of("WARN", "BLOCK");

    private final WorkspaceSettingsRepository settingsRepository;
    private final WorkspaceService workspaceService;

    public WorkspaceSettingsService(WorkspaceSettingsRepository settingsRepository,
                                    WorkspaceService workspaceService) {
        this.settingsRepository = settingsRepository;
        this.workspaceService = workspaceService;
    }

    @Transactional(readOnly = true)
    public WorkspaceSettingsResponse getSettings(UUID workspaceId, UUID actorId) {
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MEMBER);
        WorkspaceSettings settings = getOrCreate(workspaceId);
        return WorkspaceSettingsResponse.from(settings);
    }

    @Transactional
    public WorkspaceSettingsResponse updateSettings(UUID workspaceId, UUID actorId,
                                                     UpdateWorkspaceSettingsRequest req) {
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);

        if (!VALID_DRIFT_THRESHOLDS.contains(req.driftSeverityThreshold())) {
            throw new IllegalArgumentException(
                "Invalid driftSeverityThreshold: " + req.driftSeverityThreshold()
                + ". Must be one of: " + VALID_DRIFT_THRESHOLDS);
        }

        if (!VALID_MERGE_POLICIES.contains(req.mergePolicyCritical())) {
            throw new IllegalArgumentException(
                "Invalid mergePolicyCritical: " + req.mergePolicyCritical()
                + ". Must be one of: " + VALID_MERGE_POLICIES);
        }

        if (req.aiConfidenceThreshold() < 0 || req.aiConfidenceThreshold() > 1) {
            throw new IllegalArgumentException(
                "aiConfidenceThreshold must be between 0 and 1");
        }

        WorkspaceSettings settings = getOrCreate(workspaceId);
        settings.setDriftSeverityThreshold(req.driftSeverityThreshold());
        settings.setAutoUpdateOnPr(req.autoUpdateOnPr());
        settings.setAutoUpdateOnCommit(req.autoUpdateOnCommit());
        settings.setRequireManagerApproval(req.requireManagerApproval());
        settings.setMergePolicyCritical(req.mergePolicyCritical());
        settings.setAiConfidenceThreshold(req.aiConfidenceThreshold());

        WorkspaceSettings saved = settingsRepository.save(settings);
        return WorkspaceSettingsResponse.from(saved);
    }

    private WorkspaceSettings getOrCreate(UUID workspaceId) {
        return settingsRepository.findById(workspaceId)
            .orElseGet(() -> {
                WorkspaceSettings ws = new WorkspaceSettings(workspaceId);
                return settingsRepository.save(ws);
            });
    }
}
