package com.livingdocs.modules.workspace;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.dto.AddMemberRequest;
import com.livingdocs.modules.workspace.dto.CreateWorkspaceRequest;
import com.livingdocs.modules.workspace.dto.UpdateMemberRoleRequest;
import com.livingdocs.modules.workspace.dto.UpdateWorkspaceRequest;
import com.livingdocs.modules.workspace.model.Workspace;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceServiceTest {

    private WorkspaceService service;
    private WorkspaceRepository workspaceRepo;
    private WorkspaceMemberRepository memberRepo;
    private UserRepository userRepo;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        workspaceRepo = mock(WorkspaceRepository.class);
        memberRepo = mock(WorkspaceMemberRepository.class);
        userRepo = mock(UserRepository.class);
        service = new WorkspaceService(workspaceRepo, memberRepo, userRepo);

        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        when(userRepo.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user(alice, "alice@example.com")));
        when(userRepo.findByEmailIgnoreCase("bob@example.com")).thenReturn(Optional.of(user(bob, "bob@example.com")));
    }

    @Test
    void create_registersOwnerAsManagerMember() {
        when(workspaceRepo.existsBySlug("acme")).thenReturn(false);
        when(workspaceRepo.save(any(Workspace.class))).thenAnswer(inv -> {
            Workspace w = inv.getArgument(0);
            w.setIdForTest(UUID.randomUUID());
            return w;
        });
        when(memberRepo.save(any(WorkspaceMember.class))).thenAnswer(inv -> inv.getArgument(0));

        Workspace ws = service.create(alice, new CreateWorkspaceRequest(
                "Acme", "acme", "Acme workspace"));

        assertNotNull(ws.getId());
        assertEquals("acme", ws.getSlug());
        assertEquals(alice, ws.getOwnerId());

        List<WorkspaceMember> saved = org.mockito.Mockito.mockingDetails(memberRepo).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("save"))
                .map(i -> (WorkspaceMember) i.getArgument(0))
                .toList();
        assertTrue(saved.stream().anyMatch(m -> m.getUserId().equals(alice)
                && m.getRole() == WorkspaceRole.MANAGER));
    }

    @Test
    void create_duplicateSlugThrowsConflict() {
        when(workspaceRepo.existsBySlug("acme")).thenReturn(true);

        assertThrows(ConflictException.class,
                () -> service.create(alice, new CreateWorkspaceRequest("Acme", "acme", "x")));
    }

    @Test
    void addMember_persistsMemberWithRequestedRole() {
        UUID wsId = UUID.randomUUID();
        Workspace ws = newWorkspace(wsId, alice);
        when(workspaceRepo.findById(wsId)).thenReturn(Optional.of(ws));
        when(memberRepo.findByWorkspaceIdAndUserId(wsId, alice))
                .thenReturn(Optional.of(member(wsId, alice, WorkspaceRole.MANAGER)));
        when(memberRepo.findByWorkspaceIdAndUserId(wsId, bob))
                .thenReturn(Optional.empty());
        when(memberRepo.existsByWorkspaceIdAndUserId(wsId, bob)).thenReturn(false);
        when(memberRepo.save(any(WorkspaceMember.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceMember member = service.addMember(alice, wsId,
                new AddMemberRequest("bob@example.com", WorkspaceRole.MEMBER));

        assertEquals(bob, member.getUserId());
        assertEquals(WorkspaceRole.MEMBER, member.getRole());
    }

    @Test
    void addMember_nonManagerGetsForbidden() {
        UUID wsId = UUID.randomUUID();
        when(memberRepo.findByWorkspaceIdAndUserId(wsId, bob))
                .thenReturn(Optional.of(member(wsId, bob, WorkspaceRole.MEMBER)));

        assertThrows(ForbiddenException.class,
                () -> service.addMember(bob, wsId,
                        new AddMemberRequest("alice@example.com", WorkspaceRole.MEMBER)));
    }

    @Test
    void addMember_unknownEmailThrowsNotFound() {
        UUID wsId = UUID.randomUUID();
        Workspace ws = newWorkspace(wsId, alice);
        when(workspaceRepo.findById(wsId)).thenReturn(Optional.of(ws));
        when(memberRepo.findByWorkspaceIdAndUserId(wsId, alice))
                .thenReturn(Optional.of(member(wsId, alice, WorkspaceRole.MANAGER)));
        when(userRepo.findByEmailIgnoreCase("nobody@example.com")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> service.addMember(alice, wsId,
                        new AddMemberRequest("nobody@example.com", WorkspaceRole.MEMBER)));
    }

    @Test
    void listForUser_returnsWorkspacesTheUserIsAMemberOf() {
        UUID wsA = UUID.randomUUID();
        UUID wsB = UUID.randomUUID();
        Workspace a = newWorkspace(wsA, alice);
        Workspace b = newWorkspace(wsB, bob);

        when(memberRepo.findAllByUserId(alice))
                .thenReturn(List.of(member(wsA, alice, WorkspaceRole.MANAGER)));
        when(memberRepo.findAllByUserId(bob))
                .thenReturn(List.of(
                        member(wsA, bob, WorkspaceRole.MEMBER),
                        member(wsB, bob, WorkspaceRole.MANAGER)));
        when(workspaceRepo.findById(wsA)).thenReturn(Optional.of(a));
        when(workspaceRepo.findById(wsB)).thenReturn(Optional.of(b));

        List<Workspace> aliceWorkspaces = service.listForUser(alice);
        List<Workspace> bobWorkspaces = service.listForUser(bob);

        assertEquals(List.of(wsA), aliceWorkspaces.stream().map(Workspace::getId).toList());
        assertTrue(bobWorkspaces.stream().map(Workspace::getId).toList().containsAll(List.of(wsA, wsB)));
    }

    @Test
    void updateMemberRole_allowsPromotionButRejectsDemotingOwner() {
        UUID wsId = UUID.randomUUID();
        Workspace ws = newWorkspace(wsId, alice);
        when(workspaceRepo.findById(wsId)).thenReturn(Optional.of(ws));
        when(memberRepo.findByWorkspaceIdAndUserId(wsId, alice))
                .thenReturn(Optional.of(member(wsId, alice, WorkspaceRole.MANAGER)));
        when(memberRepo.findByWorkspaceIdAndUserId(wsId, bob))
                .thenReturn(Optional.of(member(wsId, bob, WorkspaceRole.MEMBER)));
        when(memberRepo.save(any(WorkspaceMember.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceMember promoted = service.updateMemberRole(alice, wsId, bob,
                new UpdateMemberRoleRequest(WorkspaceRole.MANAGER));
        assertEquals(WorkspaceRole.MANAGER, promoted.getRole());

        assertThrows(BadRequestException.class,
                () -> service.updateMemberRole(alice, wsId, alice,
                        new UpdateMemberRoleRequest(WorkspaceRole.MEMBER)));
    }

    @Test
    void delete_onlyOwnerCanDelete() {
        UUID wsId = UUID.randomUUID();
        Workspace ws = newWorkspace(wsId, alice);
        when(workspaceRepo.findById(wsId)).thenReturn(Optional.of(ws));
        when(memberRepo.findByWorkspaceIdAndUserId(wsId, bob))
                .thenReturn(Optional.of(member(wsId, bob, WorkspaceRole.MANAGER)));
        when(memberRepo.findByWorkspaceIdAndUserId(wsId, alice))
                .thenReturn(Optional.of(member(wsId, alice, WorkspaceRole.MANAGER)));

        assertThrows(ForbiddenException.class,
                () -> service.delete(bob, wsId));

        // Owner can delete (the mock will return normally).
        service.delete(alice, wsId);
        org.mockito.Mockito.verify(workspaceRepo).delete(ws);
    }

    // ---------------- helpers ----------------

    private User user(UUID id, String email) {
        User u = new User(email, "Display", "hash");
        u.setEmailForTest(id);
        return u;
    }

    private Workspace newWorkspace(UUID id, UUID owner) {
        Workspace w = new Workspace("Test", "test", null, owner);
        w.setIdForTest(id);
        return w;
    }

    private WorkspaceMember member(UUID wsId, UUID userId, WorkspaceRole role) {
        WorkspaceMember m = new WorkspaceMember(wsId, userId, role);
        m.setIdForTest(UUID.randomUUID());
        return m;
    }
}