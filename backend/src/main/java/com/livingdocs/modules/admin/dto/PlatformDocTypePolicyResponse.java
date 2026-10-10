package com.livingdocs.modules.admin.dto;

import com.livingdocs.modules.admin.model.PlatformDocTypePolicy;

import java.time.OffsetDateTime;

public record PlatformDocTypePolicyResponse(
    String docType,
    String workflow,
    OffsetDateTime updatedAt
) {
    public static PlatformDocTypePolicyResponse from(PlatformDocTypePolicy p) {
        return new PlatformDocTypePolicyResponse(
                p.getDocType(),
                p.getWorkflow().name(),
                p.getUpdatedAt()
        );
    }
}
