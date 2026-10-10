package com.livingdocs.modules.admin.dto;

import com.livingdocs.modules.admin.model.PlatformSettings;

import java.time.OffsetDateTime;

public record PlatformSettingsResponse(
    boolean defaultAutoUpdateOnCommit,
    boolean defaultAutoUpdateOnPr,
    boolean defaultAutoUpdateOnMerge,
    String defaultDriftSeverityThreshold,
    boolean defaultRequireManagerApproval,
    String defaultMergePolicyCritical,
    float defaultAiConfidenceThreshold,
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
                p.getUpdatedAt()
        );
    }
}
