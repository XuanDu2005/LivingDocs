package com.livingdocs.modules.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Payload to set the workflow a workspace applies to one documentation
 * type.
 *
 * <p>The workflow is mandatory: a workspace row either declares a
 * workflow or it does not exist. To go back to inheriting the platform
 * policy the row is deleted instead
 * ({@code DELETE /workspaces/{id}/doc-type-policies/{docType}}).
 */
public record UpdateWorkspaceDocTypePolicyRequest(
        @NotBlank
        @Pattern(regexp = "AUTO_APPLY|MANAGER_REVIEW",
                message = "must be one of AUTO_APPLY, MANAGER_REVIEW")
        String workflow
) {}
