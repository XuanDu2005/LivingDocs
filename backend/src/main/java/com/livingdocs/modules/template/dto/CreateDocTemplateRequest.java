package com.livingdocs.modules.template.dto;

import com.livingdocs.modules.template.model.OutputFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body for creating a documentation template.
 *
 * <p>{@code workspaceId} is null for global library templates.
 * {@code bodyJson} must be valid JSON describing sections / placeholders.
 */
public record CreateDocTemplateRequest(
        UUID workspaceId,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 140) String slug,
        @Size(max = 500) String description,
        @NotBlank @Size(max = 40) String docType,
        @NotBlank String bodyJson,
        OutputFormat outputFormat,
        Boolean autoGenerateOnCommit,
        Boolean autoGenerateOnPr,
        Boolean autoGenerateOnMerge,
        boolean isDefault
) {
    public OutputFormat outputFormatOrDefault() {
        return outputFormat == null ? OutputFormat.MARKDOWN : outputFormat;
    }

    public boolean autoGenerateOnCommitOrDefault() {
        return Boolean.TRUE.equals(autoGenerateOnCommit);
    }

    public boolean autoGenerateOnPrOrDefault() {
        return Boolean.TRUE.equals(autoGenerateOnPr);
    }

    public boolean autoGenerateOnMergeOrDefault() {
        return Boolean.TRUE.equals(autoGenerateOnMerge);
    }
}