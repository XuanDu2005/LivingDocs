package com.livingdocs.modules.admin.dto;

import com.livingdocs.modules.admin.model.DocOutputFormat;
import com.livingdocs.modules.admin.model.GenerationTrigger;
import com.livingdocs.modules.admin.model.PlatformSettings;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PlatformSettingsResponse(
    boolean defaultAutoUpdateOnCommit,
    boolean defaultAutoUpdateOnPr,
    boolean defaultAutoUpdateOnMerge,
    String defaultDriftSeverityThreshold,
    boolean defaultRequireManagerApproval,
    String defaultMergePolicyCritical,
    float defaultAiConfidenceThreshold,
    DocOutputFormat defaultOutputFormat,
    UUID defaultTemplateId,
    GenerationTrigger defaultGenerationTrigger,
    OffsetDateTime updatedAt
) {
    public static PlatformSettingsResponse from(PlatformSettings p) {
        return new PlatformSettingsResponse(
                p.isDefaultAutoUpdateOnCommit(),
                p.isDefaultAutoUpdateOnPr(),
                p.isDefaultAutoUpdateOnMerge(),
                p.getDefaultDriftSeverityThreshold(),
                p.isDefaultRequireManagerApproval(),
                p.getDefaultMergePolicyCritical(),
                p.getDefaultAiConfidenceThreshold(),
                p.getDefaultOutputFormat(),
                p.getDefaultTemplateId(),
                p.getDefaultGenerationTrigger(),
                p.getUpdatedAt()
        );
    }
}
