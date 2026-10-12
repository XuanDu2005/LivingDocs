package com.livingdocs.modules.workspace.service;

import com.livingdocs.modules.admin.model.DocOutputFormat;
import com.livingdocs.modules.admin.model.GenerationTrigger;
import com.livingdocs.modules.admin.model.PlatformSettings;
import com.livingdocs.modules.admin.repository.PlatformSettingsRepository;
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
    private static final Set<String> VALID_OUTPUT_FORMATS = Set.of("MARKDOWN", "HTML", "PDF");
    private static final Set<String> VALID_GENERATION_TRIGGERS =
            Set.of("MANUAL", "ON_COMMIT", "ON_PULL_REQUEST", "ON_MERGE", "SCHEDULED");

    private final WorkspaceSettingsRepository settingsRepository;
    private final WorkspaceService workspaceService;
    private final PlatformSettingsRepository platformSettingsRepository;

    public WorkspaceSettingsService(WorkspaceSettingsRepository settingsRepository,
                                    WorkspaceService workspaceService,
                                    PlatformSettingsRepository platformSettingsRepository) {
        this.settingsRepository = settingsRepository;
        this.workspaceService = workspaceService;
        this.platformSettingsRepository = platformSettingsRepository;
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

        if (req.defaultOutputFormat() != null
                && !VALID_OUTPUT_FORMATS.contains(req.defaultOutputFormat())) {
            throw new IllegalArgumentException(
                "Invalid defaultOutputFormat: " + req.defaultOutputFormat()
                + ". Must be one of: " + VALID_OUTPUT_FORMATS);
        }

        if (req.defaultGenerationTrigger() != null
                && !VALID_GENERATION_TRIGGERS.contains(req.defaultGenerationTrigger())) {
            throw new IllegalArgumentException(
                "Invalid defaultGenerationTrigger: " + req.defaultGenerationTrigger()
                + ". Must be one of: " + VALID_GENERATION_TRIGGERS);
        }

        WorkspaceSettings settings = getOrCreate(workspaceId);
        settings.setDriftSeverityThreshold(req.driftSeverityThreshold());
        settings.setAutoUpdateOnPr(req.autoUpdateOnPr());
        settings.setAutoUpdateOnCommit(req.autoUpdateOnCommit());
        settings.setRequireManagerApproval(req.requireManagerApproval());
        settings.setMergePolicyCritical(req.mergePolicyCritical());
        settings.setAiConfidenceThreshold(req.aiConfidenceThreshold());
        settings.setDefaultOutputFormat(req.defaultOutputFormat() == null
                ? null : DocOutputFormat.valueOf(req.defaultOutputFormat()));
        settings.setDefaultTemplateId(req.defaultTemplateId());
        settings.setDefaultGenerationTrigger(req.defaultGenerationTrigger() == null
                ? null : GenerationTrigger.valueOf(req.defaultGenerationTrigger()));

        WorkspaceSettings saved = settingsRepository.save(settings);
        return WorkspaceSettingsResponse.from(saved);
    }

    private WorkspaceSettings getOrCreate(UUID workspaceId) {
        return settingsRepository.findById(workspaceId)
            .orElseGet(() -> {
                WorkspaceSettings ws = new WorkspaceSettings(workspaceId);
                // Copy platform-wide defaults for new workspaces (V17
                // added the doc-generation columns).
                PlatformSettings platform = platformSettingsRepository.getSingleton();
                ws.setDriftSeverityThreshold(platform.getDefaultDriftSeverityThreshold());
                ws.setAutoUpdateOnPr(platform.isDefaultAutoUpdateOnPr());
                ws.setAutoUpdateOnCommit(platform.isDefaultAutoUpdateOnCommit());
                ws.setRequireManagerApproval(platform.isDefaultRequireManagerApproval());
                ws.setMergePolicyCritical(platform.getDefaultMergePolicyCritical());
                ws.setAiConfidenceThreshold(platform.getDefaultAiConfidenceThreshold());
                ws.setDefaultOutputFormat(platform.getDefaultOutputFormat());
                ws.setDefaultTemplateId(platform.getDefaultTemplateId());
                ws.setDefaultGenerationTrigger(platform.getDefaultGenerationTrigger());
                return settingsRepository.save(ws);
            });
    }
}
