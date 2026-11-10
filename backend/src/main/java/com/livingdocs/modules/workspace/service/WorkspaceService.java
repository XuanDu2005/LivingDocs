package com.livingdocs.modules.workspace.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.dto.AddMemberRequest;
import com.livingdocs.modules.workspace.dto.CreateWorkspaceRequest;
import com.livingdocs.modules.workspace.dto.UpdateWorkspaceRequest;
import com.livingdocs.modules.workspace.model.Workspace;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Manages workspaces and their membership.
 *
 * <p>Authorization is enforced at the service layer: every mutating
 * operation checks that the actor is at least a {@link WorkspaceRole#MANAGER}
 * of the target workspace.
 */
@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final UserRepository userRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository memberRepository,
                            UserRepository userRepository) {
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Workspace create(UUID actorId, CreateWorkspaceRequest req) {
        String slug = req.slug().toLowerCase(Locale.ROOT);
        if (workspaceRepository.existsBySlug(slug)) {
            throw new ConflictException("A workspace with this slug already exists");
        }
        Workspace ws = new Workspace(req.name(), slug, req.description(), actorId);
        Workspace saved = workspaceRepository.save(ws);
        // The creator is automatically a MANAGER member.
        memberRepository.save(new WorkspaceMember(saved.getId(), actorId, WorkspaceRole.MANAGER));
        return saved;
    }

    /**
     * Return the id of the first workspace the given user manages.
     *
     * <p>Used as a fallback when an operation has to pick a workspace on
     * the user's behalf (e.g. cloning a global template) and no explicit
     * target was supplied. Returns {@code null} when the user does not
     * manage any workspace.
     *
     * <p>The workspace {@code DOCUMENTATION_OWNER} counts here too: it
     * may edit the template library of the workspace it owns, so it is a
     * legitimate target for a clone even when it does not administer the
     * workspace.
     */
    @Transactional(readOnly = true)
    public UUID findFirstManagedWorkspaceId(UUID userId) {
        return memberRepository.findAllByUserId(userId).stream()
                .filter(m -> m.getRole() == WorkspaceRole.MANAGER
                        || m.getRole() == WorkspaceRole.DOCUMENTATION_OWNER)
                .map(WorkspaceMember::getWorkspaceId)
                .findFirst()
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Workspace getById(UUID workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new NotFoundException("Workspace not found"));
    }

    @Transactional(readOnly = true)
    public java.util.List<Workspace> listForUser(UUID userId) {
        return memberRepository.findAllByUserId(userId).stream()
                .map(m -> workspaceRepository.findById(m.getWorkspaceId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @Transactional
    public Workspace update(UUID actorId, UUID workspaceId, UpdateWorkspaceRequest req) {
        requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);
        Workspace ws = getById(workspaceId);
        ws.setName(req.name());
        ws.setDescription(req.description());
        return ws;
    }

    @Transactional(readOnly = true)
    public java.util.List<Workspace> listAll() {
        return workspaceRepository.findAll();
    }

    @Transactional
    public void delete(UUID actorId, UUID workspaceId) {
        Workspace ws = getById(workspaceId);
        if (!ws.getOwnerId().equals(actorId)) {
            throw new ForbiddenException("Only the workspace owner can delete it");
        }
        memberRepository.findAllByWorkspaceId(workspaceId)
                .forEach(memberRepository::delete);
        workspaceRepository.delete(ws);
    }

    @Transactional
    public WorkspaceMember addMember(UUID actorId, UUID workspaceId, AddMemberRequest req) {
        requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);

        String normalized = req.email().toLowerCase(Locale.ROOT);
        User user = userRepository.findByEmailIgnoreCase(normalized)
                .orElseThrow(() -> new NotFoundException("No user with that email"));

        if (memberRepository.existsByWorkspaceIdAndUserId(workspaceId, user.getId())) {
            throw new ConflictException("User is already a member of this workspace");
        }

        WorkspaceMember member = new WorkspaceMember(workspaceId, user.getId(), req.role());
        return memberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public java.util.List<WorkspaceMember> listMembers(UUID actorId, UUID workspaceId) {
        requireMember(actorId, workspaceId);
        return memberRepository.findAllByWorkspaceId(workspaceId);
    }

    @Transactional
    public WorkspaceMember updateMemberRole(UUID actorId, UUID workspaceId, UUID userId,
                                             com.livingdocs.modules.workspace.dto.UpdateMemberRoleRequest req) {
        requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);
        WorkspaceMember member = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new NotFoundException("Member not found"));

        Workspace ws = getById(workspaceId);
        if (member.getUserId().equals(ws.getOwnerId())
                && req.role() != WorkspaceRole.MANAGER) {
            throw new BadRequestException("Cannot demote the workspace owner");
        }
        member.setRole(req.role());
        return member;
    }

    @Transactional
    public void removeMember(UUID actorId, UUID workspaceId, UUID userId) {
        requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);
        WorkspaceMember member = memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new NotFoundException("Member not found"));

        Workspace ws = getById(workspaceId);
        if (member.getUserId().equals(ws.getOwnerId())) {
            throw new BadRequestException("Cannot remove the workspace owner");
        }
        memberRepository.delete(member);
    }

    /**
     * Return the caller's role in the workspace, or throw if they are not a member.
     */
    @Transactional(readOnly = true)
    public WorkspaceRole requireRole(UUID actorId, UUID workspaceId, WorkspaceRole minimum) {
        WorkspaceMember member = memberRepository.findByWorkspaceIdAndUserId(workspaceId, actorId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this workspace"));

        if (rank(member.getRole()) < rank(minimum)) {
            throw new ForbiddenException("Insufficient permissions in this workspace");
        }
        return member.getRole();
    }

    /**
     * Guard for the documentation-governance surfaces: the templates, the
     * doc-type workflows and the governance settings.
     *
     * <p>Two workspace roles may pass: {@code MANAGER}, which owns the
     * whole workspace, and {@code DOCUMENTATION_OWNER}, which owns the
     * shape of the documentation without administering the membership.
     *
     * <p>This is the <em>workspace</em> half of the two-axis model only.
     * The capability half — {@code GOVERNANCE_MANAGE} — is enforced by the
     * permission interceptor on the endpoint, so a workspace
     * DOCUMENTATION_OWNER still needs the capability to change anything.
     */
    @Transactional(readOnly = true)
    public WorkspaceRole requireDocumentationGovernance(UUID actorId, UUID workspaceId) {
        WorkspaceMember member = memberRepository.findByWorkspaceIdAndUserId(workspaceId, actorId)
                .orElseThrow(() -> new ForbiddenException("You are not a member of this workspace"));

        WorkspaceRole role = member.getRole();
        if (role != WorkspaceRole.MANAGER && role != WorkspaceRole.DOCUMENTATION_OWNER) {
            throw new ForbiddenException(
                    "Only a workspace manager or documentation owner can perform this action");
        }
        return role;
    }

    @Transactional(readOnly = true)
    public void requireMember(UUID actorId, UUID workspaceId) {
        if (!memberRepository.existsByWorkspaceIdAndUserId(workspaceId, actorId)) {
            throw new ForbiddenException("You are not a member of this workspace");
        }
    }

    /**
     * The rank of a workspace role, used by {@link #requireRole}.
     *
     * <p>{@code DOCUMENTATION_OWNER} sits between {@code MEMBER} and
     * {@code MANAGER}: it may do everything a member may do, and it owns
     * the documentation governance — but it does not administer the
     * membership, so it never satisfies a {@code MANAGER} requirement.
     */
    private int rank(WorkspaceRole role) {
        return switch (role) {
            case MEMBER -> 1;
            case DOCUMENTATION_OWNER -> 2;
            case MANAGER -> 3;
        };
    }
}