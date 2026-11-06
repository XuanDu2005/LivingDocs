package com.livingdocs.modules.workspace.dto;

import com.livingdocs.modules.admin.model.PlatformDocTypePolicy;
import com.livingdocs.modules.admin.model.PlatformDocTypePolicy.Workflow;
import com.livingdocs.modules.workspace.model.WorkspaceDocTypePolicy;

import java.time.OffsetDateTime;

/**
 * The doc-type policy as it applies to one workspace.
 *
 * <p>The response carries the <em>effective</em> workflow — the one the
 * runtime will actually enforce — together with the two rows it was
 * derived from, so the UI can explain the decision instead of only
 * showing it:
 *
 * <ul>
 *   <li>{@code workflow} — the effective workflow.</li>
 *   <li>{@code scope} — where that workflow comes from:
 *       {@code WORKSPACE} (the workspace overrode it),
 *       {@code PLATFORM} (inherited from the platform's per-doc-type
 *       policy) or {@code DEFAULT} (no row anywhere; the workspace's
 *       {@code requireManagerApproval} flag decides).</li>
 *   <li>{@code workspaceWorkflow} / {@code platformWorkflow} — the
 *       underlying rows, {@code null} when they do not exist.</li>
 * </ul>
 *
 * @param scope            where the effective workflow comes from
 * @param workflow         the effective workflow the runtime enforces
 * @param workspaceWorkflow the workspace's own row, or {@code null}
 * @param platformWorkflow the platform's row, or {@code null}
 * @param updatedAt        when the workspace row was last changed
 */
public record WorkspaceDocTypePolicyResponse(
        String docType,
        String scope,
        String workflow,
        String workspaceWorkflow,
        String platformWorkflow,
        OffsetDateTime updatedAt
) {

    public static final String SCOPE_WORKSPACE = "WORKSPACE";
    public static final String SCOPE_PLATFORM = "PLATFORM";
    public static final String SCOPE_DEFAULT = "DEFAULT";

    /**
     * Build the response from the workspace row, the platform row and
     * the fallback the workspace flag would produce.
     *
     * @param docType         the documentation type
     * @param workspaceRow    the workspace's own row, or {@code null}
     * @param platformRow     the platform's row, or {@code null}
     * @param fallback        the workflow implied by the workspace's
     *                        {@code requireManagerApproval} flag, used
     *                        when neither row exists
     */
    public static WorkspaceDocTypePolicyResponse of(String docType,
                                                    WorkspaceDocTypePolicy workspaceRow,
                                                    PlatformDocTypePolicy platformRow,
                                                    Workflow fallback) {
        Workflow effective;
        String scope;
        if (workspaceRow != null) {
            effective = workspaceRow.getWorkflow();
            scope = SCOPE_WORKSPACE;
        } else if (platformRow != null) {
            effective = platformRow.getWorkflow();
            scope = SCOPE_PLATFORM;
        } else {
            effective = fallback;
            scope = SCOPE_DEFAULT;
        }
        return new WorkspaceDocTypePolicyResponse(
                docType,
                scope,
                effective.name(),
                workspaceRow == null ? null : workspaceRow.getWorkflow().name(),
                platformRow == null ? null : platformRow.getWorkflow().name(),
                workspaceRow == null ? null : workspaceRow.getUpdatedAt()
        );
    }
}
