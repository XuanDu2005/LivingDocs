package com.livingdocs.common.security;

import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for "what may this user do?".
 *
 * <h2>The two-axis model</h2>
 * LivingDocs has two independent axes of authority:
 * <ul>
 *   <li><b>Platform role</b> — assigned by an administrator, stored in
 *       {@code user_roles}. Values: {@code DEVELOPER}, {@code STAFF},
 *       {@code TECHNICAL_LEAD}, {@code MANAGER}, {@code ADMIN}.</li>
 *   <li><b>Workspace role</b> — assigned per workspace, stored in
 *       {@code workspace_members}. Values: {@code MEMBER},
 *       {@code MANAGER}.</li>
 * </ul>
 *
 * <p>Enforcement is <b>AND</b>: to pass a guard the actor needs the
 * required capability from <em>both</em> axes. A platform {@code ADMIN}
 * who is not a member of the workspace cannot approve a document in it,
 * because the workspace role is what proves the person has a stake in
 * that workspace's content. This keeps the platform role from being a
 * backdoor around per-workspace scoping.
 *
 * <h2>Capability mapping</h2>
 * Platform roles map onto review capabilities as follows:
 * <ul>
 *   <li>{@code DEVELOPER} — read and contribute only.</li>
 *   <li>{@code STAFF} — first-tier review (technical correctness).</li>
 *   <li>{@code TECHNICAL_LEAD} — Staff duties <em>plus</em> Manager
 *       duties: it can approve and publish.</li>
 *   <li>{@code MANAGER} — final approval, publishing, workspace
 *       administration.</li>
 *   <li>{@code ADMIN} — full platform access; also satisfies the
 *       workspace requirement for administrative operations.</li>
 * </ul>
 *
 * <p>Note that {@code TECHNICAL_LEAD} intentionally does <em>not</em>
 * imply workspace administration — managing members and repository
 * connections stays with the workspace {@code MANAGER}.
 */
@Service
public class AuthorizationService {

    /**
     * The platform roles a user must hold to pass a review-stage guard.
     */
    public static final String ROLE_DEVELOPER = "DEVELOPER";
    public static final String ROLE_STAFF = "STAFF";
    public static final String ROLE_TECHNICAL_LEAD = "TECHNICAL_LEAD";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_ADMIN = "ADMIN";

    /** Platform roles that can perform the first-tier (Staff) review. */
    private static final Set<String> STAFF_CAPABLE = Set.of(
            ROLE_STAFF, ROLE_TECHNICAL_LEAD, ROLE_MANAGER, ROLE_ADMIN);

    /** Platform roles that can perform the second-tier (Manager) approval. */
    private static final Set<String> MANAGER_CAPABLE = Set.of(
            ROLE_TECHNICAL_LEAD, ROLE_MANAGER, ROLE_ADMIN);

    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final WorkspaceMemberRepository memberRepository;

    public AuthorizationService(UserRoleAssignmentRepository roleAssignmentRepository,
                                 WorkspaceMemberRepository memberRepository) {
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.memberRepository = memberRepository;
    }

    // -----------------------------------------------------------------
    // platform roles
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Set<String> platformRoles(UUID userId) {
        if (userId == null) {
            return Set.of();
        }
        return Set.copyOf(roleAssignmentRepository.findActiveRoleCodesByUserId(userId));
    }

    @Transactional(readOnly = true)
    public boolean hasPlatformRole(UUID userId, String roleCode) {
        return platformRoles(userId).contains(roleCode);
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(UUID userId) {
        return hasPlatformRole(userId, ROLE_ADMIN);
    }

    /**
     * Assert the user holds at least one of the given platform roles.
     */
    @Transactional(readOnly = true)
    public void requireAnyPlatformRole(UUID userId, String... roleCodes) {
        Set<String> held = platformRoles(userId);
        for (String code : roleCodes) {
            if (held.contains(code)) {
                return;
            }
        }
        throw new ForbiddenException(
                "This action requires one of these roles: " + String.join(", ", roleCodes));
    }

    // -----------------------------------------------------------------
    // workspace roles
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public boolean isWorkspaceMember(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return false;
        }
        return memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId);
    }

    @Transactional(readOnly = true)
    public WorkspaceRole workspaceRole(UUID userId, UUID workspaceId) {
        return memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
                .map(WorkspaceMember::getRole)
                .orElse(null);
    }

    // -----------------------------------------------------------------
    // combined guards
    // -----------------------------------------------------------------

    /**
     * Guard for the first-tier (Staff) review.
     *
     * <p>Requires a platform role that can review, <em>and</em> workspace
     * membership.
     */
    @Transactional(readOnly = true)
    public void requireStaffReviewer(UUID userId, UUID workspaceId) {
        requireAnyPlatformRole(userId, ROLE_STAFF, ROLE_TECHNICAL_LEAD, ROLE_MANAGER, ROLE_ADMIN);
        requireWorkspaceMember(userId, workspaceId);
    }

    /**
     * Guard for the second-tier (Manager) approval and for publishing.
     *
     * <p>Requires a platform role that can approve, <em>and</em> workspace
     * membership.
     */
    @Transactional(readOnly = true)
    public void requireManagerApprover(UUID userId, UUID workspaceId) {
        requireAnyPlatformRole(userId, ROLE_TECHNICAL_LEAD, ROLE_MANAGER, ROLE_ADMIN);
        requireWorkspaceMember(userId, workspaceId);
    }

    /**
     * Guard for workspace administration (members, repository connections).
     *
     * <p>Requires the workspace {@code MANAGER} role. Platform roles do
     * not substitute for it: even an {@code ADMIN} must be a workspace
     * manager to manage that workspace's members.
     */
    @Transactional(readOnly = true)
    public void requireWorkspaceManager(UUID userId, UUID workspaceId) {
        WorkspaceRole role = workspaceRole(userId, workspaceId);
        if (role != WorkspaceRole.MANAGER) {
            throw new ForbiddenException("Only a workspace manager can perform this action");
        }
    }

    @Transactional(readOnly = true)
    public void requireWorkspaceMember(UUID userId, UUID workspaceId) {
        if (!isWorkspaceMember(userId, workspaceId)) {
            throw new ForbiddenException("You are not a member of this workspace");
        }
    }

    /**
     * Compute the effective capabilities of a user in a workspace.
     *
     * <p>Used by the frontend to decide which buttons to render, so the
     * UI never shows an action the API would reject.
     */
    @Transactional(readOnly = true)
    public Capabilities capabilities(UUID userId, UUID workspaceId) {
        Set<String> roles = platformRoles(userId);
        WorkspaceRole wsRole = workspaceRole(userId, workspaceId);
        boolean member = wsRole != null;

        boolean staff = member && roles.stream().anyMatch(STAFF_CAPABLE::contains);
        boolean manager = member && roles.stream().anyMatch(MANAGER_CAPABLE::contains);
        boolean workspaceManager = wsRole == WorkspaceRole.MANAGER;

        return new Capabilities(
                member,
                workspaceManager,
                staff,
                manager,
                roles.contains(ROLE_ADMIN),
                List.copyOf(roles));
    }

    /**
     * The capability snapshot for one user in one workspace.
     */
    public record Capabilities(
            boolean member,
            boolean workspaceManager,
            boolean canStaffReview,
            boolean canManagerApprove,
            boolean admin,
            List<String> platformRoles) {}
}
