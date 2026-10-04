package com.livingdocs.modules.workspace.dto;

import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkspaceMemberResponse(
        UUID id,
        UUID workspaceId,
        UUID userId,
        String userEmail,
        String displayName,
        WorkspaceRole role,
        OffsetDateTime joinedAt
) {
    public static WorkspaceMemberResponse of(WorkspaceMember m, String email, String displayName) {
        return new WorkspaceMemberResponse(
                m.getId(),
                m.getWorkspaceId(),
                m.getUserId(),
                email,
                displayName,
                m.getRole(),
                m.getJoinedAt()
        );
    }
}