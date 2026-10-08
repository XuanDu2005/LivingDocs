package com.livingdocs.modules.template.dto;

import com.livingdocs.modules.template.model.OutputFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for updating an existing documentation template. The slug is immutable;
 * a new version is created on every save so templates can be rolled back.
 */
public record UpdateDocTemplateRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 500) String description,
        @NotBlank String bodyJson,
        OutputFormat outputFormat,
        Boolean autoGenerateOnCommit,
        Boolean autoGenerateOnPr,
        Boolean autoGenerateOnMerge,
        boolean isDefault
) {
    public boolean autoGenerateOnCommitOrDefault(boolean previous) {
        return autoGenerateOnCommit == null ? previous : autoGenerateOnCommit;
    }

    public boolean autoGenerateOnPrOrDefault(boolean previous) {
        return autoGenerateOnPr == null ? previous : autoGenerateOnPr;
    }

    public boolean autoGenerateOnMergeOrDefault(boolean previous) {
        return autoGenerateOnMerge == null ? previous : autoGenerateOnMerge;
    }
}