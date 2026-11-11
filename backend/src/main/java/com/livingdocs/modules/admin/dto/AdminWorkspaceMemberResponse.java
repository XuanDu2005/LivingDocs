package com.livingdocs.modules.admin.dto;

import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Administrator-facing projection of a {@link WorkspaceMember}.
 *
 * <p>It carries the user's identity (email, display name) so the
 * administrator does not have to make a second call per member to
 * know who they are looking at, and it flags the workspace owner so
 * the UI can refuse to demote or remove them.
 */
public record AdminWorkspaceMemberResponse(
        UUID id,
        UUID workspaceId,
        UUID userId,
        String userEmail,
        String displayName,
        WorkspaceRole role,
        boolean owner,
        OffsetDateTime joinedAt
) {
    public static AdminWorkspaceMemberResponse of(WorkspaceMember m,
                                                   String email,
                                                   String displayName,
                                                   UUID workspaceOwnerId) {
        return new AdminWorkspaceMemberResponse(
                m.getId(),
                m.getWorkspaceId(),
                m.getUserId(),
                email,
                displayName,
                m.getRole(),
                m.getUserId().equals(workspaceOwnerId),
                m.getJoinedAt()
        );
    }
}
