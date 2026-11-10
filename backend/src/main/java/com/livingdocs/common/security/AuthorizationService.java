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
 *       {@code DOCUMENTATION_OWNER}, {@code MANAGER}.</li>
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
 *
 * <p>The same is true of the workspace {@code DOCUMENTATION_OWNER}: it
 * owns the documentation governance of one workspace (the templates, the
 * doc-type workflows, the governance settings) without administering that
 * workspace, and it grants nothing on its own — the capability axis
 * still has to agree.
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

    /**
     * Platform roles that can perform the second-tier (Manager) approval.
     *
     * @deprecated The capability now comes from the permission matrix —
     *     see {@link #capabilities(UUID, UUID)}. Kept only as the
     *     documented list of which roles are *expected* to hold the
     *     approve/publish grants; the seed in V40 asserts it.
     */
    @Deprecated
    private static final Set<String> MANAGER_CAPABLE = Set.of(
            ROLE_TECHNICAL_LEAD, ROLE_MANAGER, ROLE_ADMIN);

    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final PermissionService permissionService;

    public AuthorizationService(UserRoleAssignmentRepository roleAssignmentRepository,
                                 WorkspaceMemberRepository memberRepository,
                                 PermissionService permissionService) {
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.memberRepository = memberRepository;
        this.permissionService = permissionService;
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
     *
     * @deprecated The role list was hard-coded, which made the permission
     *     matrix decorative: revoking {@code DOCUMENT_VERSION_REVIEW} from
     *     the STAFF role in the admin UI had no effect on this guard.
     *     Use {@link #requireDocumentReviewer(UUID, UUID)} instead — it
     *     resolves the capability from the matrix.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public void requireStaffReviewer(UUID userId, UUID workspaceId) {
        requireAnyPlatformRole(userId, ROLE_STAFF, ROLE_TECHNICAL_LEAD, ROLE_MANAGER, ROLE_ADMIN);
        requireWorkspaceMember(userId, workspaceId);
    }

    /**
     * Guard for the second-tier (Manager) approval.
     *
     * <p>Requires a platform role that can approve, <em>and</em> workspace
     * membership.
     *
     * @deprecated Same problem as {@link #requireStaffReviewer}: the role
     *     list was hard-coded. Use
     *     {@link #requireDocumentApprover(UUID, UUID)} instead.
     */
    @Deprecated
    @Transactional(readOnly = true)
    public void requireManagerApprover(UUID userId, UUID workspaceId) {
        requireAnyPlatformRole(userId, ROLE_TECHNICAL_LEAD, ROLE_MANAGER, ROLE_ADMIN);
        requireWorkspaceMember(userId, workspaceId);
    }

    /**
     * Guard for the first-tier (Staff) review, resolved from the
     * permission matrix.
     *
     * <p>The two axes stay separated on purpose:
     * <ul>
     *   <li>the <b>permission</b> answers "may this person review at
     *       all?" — it is resolved from {@code role_permissions} so an
     *       administrator can revoke {@code DOCUMENT_VERSION_REVIEW}
     *       without touching code;</li>
     *   <li>the <b>workspace membership</b> answers "may this person
     *       act on <em>this</em> workspace's content?" — a platform
     *       {@code ADMIN} who is not a member must not be able to review
     *       a document in it.</li>
     * </ul>
     *
     * <p>{@code ADMIN} keeps its blanket bypass: the matrix pins its row
     * to "every permission granted", and an operator must never be able
     * to lock the platform out of its own review surface.
     */
    @Transactional(readOnly = true)
    public void requireDocumentReviewer(UUID userId, UUID workspaceId) {
        if (!isAdmin(userId)) {
            permissionService.requireAnyPermission(userId, PermissionCodes.DOCUMENT_VERSION_REVIEW);
        }
        requireWorkspaceMember(userId, workspaceId);
    }

    /**
     * Guard for the second-tier (Manager) approval, resolved from the
     * permission matrix.
     *
     * @see #requireDocumentReviewer(UUID, UUID)
     */
    @Transactional(readOnly = true)
    public void requireDocumentApprover(UUID userId, UUID workspaceId) {
        if (!isAdmin(userId)) {
            permissionService.requireAnyPermission(userId, PermissionCodes.DOCUMENT_VERSION_APPROVE);
        }
        requireWorkspaceMember(userId, workspaceId);
    }

    /**
     * Guard for publishing an approved version, resolved from the
     * permission matrix.
     *
     * <p>Publishing used to reuse the Manager-approval guard, which meant
     * a role could publish without the matrix saying so. The publish
     * capability is now its own permission.
     */
    @Transactional(readOnly = true)
    public void requireDocumentPublisher(UUID userId, UUID workspaceId) {
        if (!isAdmin(userId)) {
            permissionService.requireAnyPermission(userId, PermissionCodes.DOCUMENT_VERSION_PUBLISH);
        }
        requireWorkspaceMember(userId, workspaceId);
    }

    /**
     * Guard for resolving a documentation drift alert.
     *
     * <p>Resolving a drift alert is a judgement about the documentation:
     * "the code changed and the documentation is now wrong" versus "the
     * documentation is still correct, the analysis was wrong". That
     * judgement belongs to the people who review content, which is why
     * it is a capability of its own ({@code DRIFT_RESOLVE}) rather than
     * plain workspace membership.
     *
     * <p>Note the asymmetry with {@code DRIFT_DETECT_RUN} on the
     * detection side: detecting drift is a machine action that anybody
     * may trigger, so it stays a member-level operation.
     */
    @Transactional(readOnly = true)
    public void requireDriftResolver(UUID userId, UUID workspaceId) {
        if (!isAdmin(userId)) {
            permissionService.requireAnyPermission(userId, PermissionCodes.DRIFT_RESOLVE);
        }
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
     * Guard for the documentation-governance surfaces: the templates, the
     * doc-type workflows and the governance settings.
     *
     * <p>Two workspace roles pass: {@code MANAGER}, which owns the whole
     * workspace, and {@code DOCUMENTATION_OWNER}, which owns the shape of
     * the documentation without administering the membership.
     *
     * <p>This is the workspace half of the AND rule only — the caller
     * also needs the {@code GOVERNANCE_MANAGE} capability, which the
     * permission interceptor checks on the endpoint.
     */
    @Transactional(readOnly = true)
    public void requireDocumentationGovernance(UUID userId, UUID workspaceId) {
        WorkspaceRole role = workspaceRole(userId, workspaceId);
        if (role != WorkspaceRole.MANAGER && role != WorkspaceRole.DOCUMENTATION_OWNER) {
            throw new ForbiddenException(
                    "Only a workspace manager or documentation owner can perform this action");
        }
    }

    /**
     * Compute the effective capabilities of a user in a workspace.
     *
     * <p>Used by the frontend to decide which buttons to render, so the
     * UI never shows an action the API would reject.
     *
     * <p>The review capabilities are resolved from the <em>permission
     * matrix</em>, not from the platform role list. The two used to be
     * equivalent, but only the matrix is editable at runtime — an
     * administrator who revokes {@code DOCUMENT_VERSION_REVIEW} from
     * STAFF must see the review buttons disappear, and the API must
     * answer 403. Deriving the capability from the role list made the
     * matrix decorative.
     *
     * <p>{@code ADMIN} keeps its blanket bypass, mirroring
     * {@link PermissionInterceptor}.
     */
    @Transactional(readOnly = true)
    public Capabilities capabilities(UUID userId, UUID workspaceId) {
        Set<String> roles = platformRoles(userId);
        WorkspaceRole wsRole = workspaceRole(userId, workspaceId);
        boolean member = wsRole != null;
        boolean admin = roles.contains(ROLE_ADMIN);

        boolean staff = member && (admin || permissionService.hasAnyPermission(
                userId, PermissionCodes.DOCUMENT_VERSION_REVIEW));
        boolean manager = member && (admin || permissionService.hasAnyPermission(
                userId, PermissionCodes.DOCUMENT_VERSION_APPROVE));
        boolean documentationOwner = member && wsRole == WorkspaceRole.DOCUMENTATION_OWNER;

        return new Capabilities(
                member,
                member && wsRole == WorkspaceRole.MANAGER,
                documentationOwner,
                staff,
                manager,
                admin,
                List.copyOf(roles));
    }

    /**
     * The capability snapshot for one user in one workspace.
     */
    public record Capabilities(
            boolean member,
            boolean workspaceManager,
            boolean documentationOwner,
            boolean canStaffReview,
            boolean canManagerApprove,
            boolean admin,
            List<String> platformRoles) {}
}
