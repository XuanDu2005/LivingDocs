package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.AdminWorkspaceMemberResponse;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.dto.AddMemberRequest;
import com.livingdocs.modules.workspace.dto.UpdateMemberRoleRequest;
import com.livingdocs.modules.workspace.model.Workspace;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cross-tenant membership management an administrator performs on a
 * workspace they may not even belong to.
 *
 * <p>The point of the admin variants is that the workspace-level
 * {@code MANAGER} requirement does not apply — an administrator is not
 * required to be a member of the workspace they administer. What
 * <em>does</em> apply are the domain rules: the user has to exist, a
 * user cannot be a member twice, and the workspace owner is protected
 * from being demoted below {@code MANAGER} or removed.
 */
class AdminWorkspaceServiceTest {

    private WorkspaceRepository workspaceRepository;
    private WorkspaceMemberRepository memberRepository;
    private WorkspaceService workspaceService;
    private UserRepository userRepository;
    private AuditLogService auditLogService;

    private AdminWorkspaceService service;

    private UUID actorId;
    private UUID workspaceId;
    private UUID ownerId;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(WorkspaceRepository.class);
        memberRepository = mock(WorkspaceMemberRepository.class);
        workspaceService = mock(WorkspaceService.class);
        userRepository = mock(UserRepository.class);
        auditLogService = mock(AuditLogService.class);

        service = new AdminWorkspaceService(workspaceRepository, memberRepository,
                workspaceService, userRepository, auditLogService);

        actorId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        memberId = UUID.randomUUID();

        Workspace ws = new Workspace("Acme", "acme", null, ownerId);
        ws.setIdForTest(workspaceId);
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(ws));
    }

    // -----------------------------------------------------------------
    // Listing
    // -----------------------------------------------------------------

    /**
     * The members are listed even though the administrator is not a
     * member of the workspace themselves — that is the whole point of
     * the cross-tenant endpoint.
     */
    @Test
    void theMembersAreListedAcrossTheTenantBoundary() {
        WorkspaceMember member = member(workspaceId, memberId, WorkspaceRole.MEMBER);
        when(memberRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(member));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(memberId, "a@example.test")));

        List<AdminWorkspaceMemberResponse> members = service.listMembers(workspaceId);

        assertEquals(1, members.size());
        assertEquals("a@example.test", members.get(0).userEmail());
        assertFalse(members.get(0).owner());
    }

    /**
     * The row of the account the workspace points at through
     * {@code workspaces.owner_id} is flagged, so the UI can protect it.
     */
    @Test
    void theOwnerIsFlaggedInTheMemberList() {
        WorkspaceMember owner = member(workspaceId, ownerId, WorkspaceRole.MANAGER);
        when(memberRepository.findAllByWorkspaceId(workspaceId)).thenReturn(List.of(owner));
        when(userRepository.findAllById(any())).thenReturn(List.of(user(ownerId, "owner@example.test")));

        List<AdminWorkspaceMemberResponse> members = service.listMembers(workspaceId);

        assertTrue(members.get(0).owner());
    }

    // -----------------------------------------------------------------
    // Adding
    // -----------------------------------------------------------------

    /**
     * Adding a member goes through the guard-free variant of the
     * workspace service: the administrator does not have to be a
     * MANAGER of the workspace.
     */
    @Test
    void addingAMemberBypassesTheWorkspaceManagerCheck() {
        WorkspaceMember saved = member(workspaceId, memberId, WorkspaceRole.MEMBER);
        when(workspaceService.addMemberAsAdmin(workspaceId,
                new AddMemberRequest("a@example.test", WorkspaceRole.MEMBER)))
                .thenReturn(saved);
        when(userRepository.findAllById(any())).thenReturn(List.of(user(memberId, "a@example.test")));

        AdminWorkspaceMemberResponse out = service.addMember(actorId, workspaceId,
                new AddMemberRequest("a@example.test", WorkspaceRole.MEMBER));

        assertEquals(memberId, out.userId());
        verify(workspaceService).addMemberAsAdmin(eq(workspaceId), any(AddMemberRequest.class));
    }

    /**
     * The addition is audited.
     */
    @Test
    void addingAMemberIsAudited() {
        WorkspaceMember saved = member(workspaceId, memberId, WorkspaceRole.MEMBER);
        when(workspaceService.addMemberAsAdmin(eq(workspaceId), any(AddMemberRequest.class)))
                .thenReturn(saved);
        when(userRepository.findAllById(any())).thenReturn(List.of(user(memberId, "a@example.test")));

        service.addMember(actorId, workspaceId,
                new AddMemberRequest("a@example.test", WorkspaceRole.MEMBER));

        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(eq(actorId), anyString(), eq("workspace.member.add"),
                eq("workspace_member"), anyString(), eq(workspaceId), payload.capture());
        assertEquals("MEMBER", payload.getValue().get("role"));
    }

    // -----------------------------------------------------------------
    // Changing the role
    // -----------------------------------------------------------------

    /**
     * The role change goes through the guard-free variant too.
     */
    @Test
    void changingTheRoleBypassesTheWorkspaceManagerCheck() {
        WorkspaceMember promoted = member(workspaceId, memberId, WorkspaceRole.MANAGER);
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, memberId))
                .thenReturn(Optional.of(member(workspaceId, memberId, WorkspaceRole.MEMBER)));
        when(workspaceService.updateMemberRoleAsAdmin(workspaceId, memberId,
                new UpdateMemberRoleRequest(WorkspaceRole.MANAGER)))
                .thenReturn(promoted);
        when(userRepository.findAllById(any())).thenReturn(List.of(user(memberId, "a@example.test")));

        AdminWorkspaceMemberResponse out = service.updateMemberRole(actorId, workspaceId, memberId,
                new UpdateMemberRoleRequest(WorkspaceRole.MANAGER));

        assertEquals(WorkspaceRole.MANAGER, out.role());
        verify(workspaceService).updateMemberRoleAsAdmin(eq(workspaceId), eq(memberId),
                any(UpdateMemberRoleRequest.class));
    }

    /**
     * The audit entry records the role the member held before, so the
     * trail says what changed and not only what it is now.
     */
    @Test
    void changingTheRoleIsAuditedWithBothRoles() {
        WorkspaceMember promoted = member(workspaceId, memberId, WorkspaceRole.MANAGER);
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, memberId))
                .thenReturn(Optional.of(member(workspaceId, memberId, WorkspaceRole.MEMBER)));
        when(workspaceService.updateMemberRoleAsAdmin(eq(workspaceId), eq(memberId), any()))
                .thenReturn(promoted);
        when(userRepository.findAllById(any())).thenReturn(List.of(user(memberId, "a@example.test")));

        service.updateMemberRole(actorId, workspaceId, memberId,
                new UpdateMemberRoleRequest(WorkspaceRole.MANAGER));

        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(eq(actorId), anyString(), eq("workspace.member.role.change"),
                eq("workspace_member"), anyString(), eq(workspaceId), payload.capture());
        assertEquals("MEMBER", payload.getValue().get("previousRole"));
        assertEquals("MANAGER", payload.getValue().get("role"));
    }

    /**
     * The owner cannot be demoted below {@code MANAGER}: the
     * {@code workspaces.owner_id} foreign key points at that account and
     * demoting it would strip the workspace of the administrator it is
     * guaranteed to have.
     */
    @Test
    void theOwnerCannotBeDemoted() {
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, ownerId))
                .thenReturn(Optional.of(member(workspaceId, ownerId, WorkspaceRole.MANAGER)));
        when(workspaceService.updateMemberRoleAsAdmin(eq(workspaceId), eq(ownerId), any()))
                .thenThrow(new BadRequestException("Cannot demote the workspace owner"));

        assertThrows(BadRequestException.class,
                () -> service.updateMemberRole(actorId, workspaceId, ownerId,
                        new UpdateMemberRoleRequest(WorkspaceRole.MEMBER)));
    }

    // -----------------------------------------------------------------
    // Removing
    // -----------------------------------------------------------------

    /**
     * Removing a member goes through the guard-free variant.
     */
    @Test
    void removingAMemberBypassesTheWorkspaceManagerCheck() {
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, memberId))
                .thenReturn(Optional.of(member(workspaceId, memberId, WorkspaceRole.MEMBER)));

        service.removeMember(actorId, workspaceId, memberId);

        verify(workspaceService).removeMemberAsAdmin(workspaceId, memberId);
    }

    /**
     * The removal is audited.
     */
    @Test
    void removingAMemberIsAudited() {
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, memberId))
                .thenReturn(Optional.of(member(workspaceId, memberId, WorkspaceRole.MEMBER)));

        service.removeMember(actorId, workspaceId, memberId);

        verify(auditLogService).record(eq(actorId), anyString(), eq("workspace.member.remove"),
                eq("workspace_member"), eq(memberId.toString()), eq(workspaceId), any());
    }

    /**
     * The owner cannot be removed — the workspace would be left
     * pointing at a membership row that no longer exists.
     */
    @Test
    void theOwnerCannotBeRemoved() {
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, ownerId))
                .thenReturn(Optional.of(member(workspaceId, ownerId, WorkspaceRole.MANAGER)));
        // The guard lives in the workspace service; it refuses to remove
        // the owner, so the exception surfaces here.
        doThrow(new BadRequestException("Cannot remove the workspace owner"))
                .when(workspaceService).removeMemberAsAdmin(workspaceId, ownerId);

        assertThrows(BadRequestException.class,
                () -> service.removeMember(actorId, workspaceId, ownerId));
    }

    // -----------------------------------------------------------------
    // Unknown workspace
    // -----------------------------------------------------------------

    /**
     * Every membership operation on a workspace that does not exist is
     * a 404, not a silent success.
     */
    @Test
    void anUnknownWorkspaceIsRefused() {
        UUID missing = UUID.randomUUID();
        when(workspaceRepository.findById(missing)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.listMembers(missing));
        assertThrows(NotFoundException.class,
                () -> service.addMember(actorId, missing,
                        new AddMemberRequest("a@example.test", WorkspaceRole.MEMBER)));
        assertThrows(NotFoundException.class,
                () -> service.removeMember(actorId, missing, memberId));
    }

    // ---------------- helpers ----------------

    private User user(UUID id, String email) {
        User u = new User(email, "Display", "hash");
        u.setEmailForTest(id);
        return u;
    }

    private WorkspaceMember member(UUID wsId, UUID userId, WorkspaceRole role) {
        WorkspaceMember m = new WorkspaceMember(wsId, userId, role);
        m.setIdForTest(UUID.randomUUID());
        return m;
    }
}
