package com.livingdocs.modules.workspace.dto;

import com.livingdocs.modules.admin.model.DocOutputFormat;
import com.livingdocs.modules.admin.model.GenerationTrigger;
import com.livingdocs.modules.workspace.model.WorkspaceSettings;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkspaceSettingsResponse(
    UUID workspaceId,
    String driftSeverityThreshold,
    boolean autoUpdateOnPr,
    boolean autoUpdateOnCommit,
    boolean requireManagerApproval,
    String mergePolicyCritical,
    float aiConfidenceThreshold,
    DocOutputFormat defaultOutputFormat,
    UUID defaultTemplateId,
    GenerationTrigger defaultGenerationTrigger,
    OffsetDateTime updatedAt
) {
    public static WorkspaceSettingsResponse from(WorkspaceSettings ws) {
        return new WorkspaceSettingsResponse(
            ws.getWorkspaceId(),
            ws.getDriftSeverityThreshold(),
            ws.isAutoUpdateOnPr(),
            ws.isAutoUpdateOnCommit(),
            ws.isRequireManagerApproval(),
            ws.getMergePolicyCritical(),
            ws.getAiConfidenceThreshold(),
            ws.getDefaultOutputFormat(),
            ws.getDefaultTemplateId(),
            ws.getDefaultGenerationTrigger(),
            ws.getUpdatedAt()
        );
    }
}
