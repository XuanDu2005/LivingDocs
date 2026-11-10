package com.livingdocs.common.security;

import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the two-axis authorization model.
 *
 * <p>The central rule is that platform role and workspace membership are
 * combined with AND: a platform {@code ADMIN} who is not a member of the
 * workspace must not be able to approve content in it.
 */
class AuthorizationServiceTest {

    private AuthorizationService service;
    private UserRoleAssignmentRepository roleRepository;
    private WorkspaceMemberRepository memberRepository;
    private PermissionService permissionService;

    /**
     * The permission codes the user under test holds.
     *
     * <p>Both the boolean query and the void guard read from this set,
     * so the two can never disagree — which is exactly how the
     * production {@link PermissionService} behaves.
     */
    private final Set<String> granted = new HashSet<>();

    private UUID userId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        roleRepository = mock(UserRoleAssignmentRepository.class);
        memberRepository = mock(WorkspaceMemberRepository.class);
        permissionService = mock(PermissionService.class);
        service = new AuthorizationService(roleRepository, memberRepository, permissionService);

        userId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        when(roleRepository.findActiveRoleCodesByUserId(any())).thenReturn(List.of());
        when(memberRepository.existsByWorkspaceIdAndUserId(any(), any())).thenReturn(false);
        when(memberRepository.findByWorkspaceIdAndUserId(any(), any())).thenReturn(Optional.empty());

        // The boolean query answers from the granted set.
        when(permissionService.hasAnyPermission(any(), any(String[].class)))
                .thenAnswer(inv -> {
                    for (Object arg : inv.getArguments()) {
                        if (arg instanceof String[] codes) {
                            for (String code : codes) {
                                if (granted.contains(code)) {
                                    return true;
                                }
                            }
                        } else if (arg instanceof String code && granted.contains(code)) {
                            return true;
                        }
                    }
                    return false;
                });

        // The void guard refuses exactly like the real one when the
        // user holds none of the requested codes.
        doAnswer(inv -> {
            UUID actor = inv.getArgument(0);
            String[] requested = inv.getArgument(1, String[].class);
            if (permissionService.hasAnyPermission(actor, requested)) {
                return null;
            }
            throw new ForbiddenException(
                    "This action requires one of these permissions: "
                            + String.join(", ", requested));
        }).when(permissionService).requireAnyPermission(any(), any(String[].class));
    }

    // -----------------------------------------------------------------
    // platform role guards
    // -----------------------------------------------------------------

    @Test
    void developerCannotPerformStaffReview() {
        givenPlatformRoles("DEVELOPER");
        givenMembership(WorkspaceRole.MEMBER);

        assertThrows(ForbiddenException.class,
                () -> service.requireStaffReviewer(userId, workspaceId));
    }

    @Test
    void staffCanPerformStaffReview() {
        givenPlatformRoles("STAFF");
        givenMembership(WorkspaceRole.MEMBER);

        assertDoesNotThrow(() -> service.requireStaffReviewer(userId, workspaceId));
    }

    @Test
    void technicalLeadCanPerformBothTiers() {
        givenPlatformRoles("TECHNICAL_LEAD");
        givenMembership(WorkspaceRole.MEMBER);

        assertDoesNotThrow(() -> service.requireStaffReviewer(userId, workspaceId));
        assertDoesNotThrow(() -> service.requireManagerApprover(userId, workspaceId));
    }

    @Test
    void staffCannotPerformManagerApproval() {
        givenPlatformRoles("STAFF");
        givenMembership(WorkspaceRole.MEMBER);

        assertThrows(ForbiddenException.class,
                () -> service.requireManagerApprover(userId, workspaceId));
    }

    @Test
    void managerAndAdminCanApprove() {
        givenPlatformRoles("MANAGER");
        givenMembership(WorkspaceRole.MEMBER);
        assertDoesNotThrow(() -> service.requireManagerApprover(userId, workspaceId));

        givenPlatformRoles("ADMIN");
        givenMembership(WorkspaceRole.MEMBER);
        assertDoesNotThrow(() -> service.requireManagerApprover(userId, workspaceId));
    }

    // -----------------------------------------------------------------
    // the AND rule
    // -----------------------------------------------------------------

    @Test
    void platformRoleWithoutMembershipIsRejected() {
        givenPlatformRoles("ADMIN");
        givenNoMembership();

        assertThrows(ForbiddenException.class,
                () -> service.requireStaffReviewer(userId, workspaceId));
        assertThrows(ForbiddenException.class,
                () -> service.requireManagerApprover(userId, workspaceId));
    }

    @Test
    void membershipWithoutPlatformRoleIsRejected() {
        givenPlatformRoles("DEVELOPER");
        givenMembership(WorkspaceRole.MEMBER);

        assertThrows(ForbiddenException.class,
                () -> service.requireStaffReviewer(userId, workspaceId));
    }

    // -----------------------------------------------------------------
    // workspace administration
    // -----------------------------------------------------------------

    @Test
    void workspaceManagerRoleIsRequiredForAdministration() {
        givenPlatformRoles("ADMIN");
        givenMembership(WorkspaceRole.MEMBER);

        assertThrows(ForbiddenException.class,
                () -> service.requireWorkspaceManager(userId, workspaceId));
    }

    @Test
    void workspaceManagerCanAdminister() {
        givenPlatformRoles("DEVELOPER");
        givenMembership(WorkspaceRole.MANAGER);

        assertDoesNotThrow(() -> service.requireWorkspaceManager(userId, workspaceId));
    }

    // -----------------------------------------------------------------
    // documentation governance
    // -----------------------------------------------------------------

    @Test
    void documentationGovernanceAcceptsTheWorkspaceManager() {
        givenPlatformRoles("DEVELOPER");
        givenMembership(WorkspaceRole.MANAGER);

        assertDoesNotThrow(() -> service.requireDocumentationGovernance(userId, workspaceId));
    }

    @Test
    void documentationGovernanceAcceptsTheDocumentationOwner() {
        givenPlatformRoles("DEVELOPER");
        givenMembership(WorkspaceRole.DOCUMENTATION_OWNER);

        assertDoesNotThrow(() -> service.requireDocumentationGovernance(userId, workspaceId));
    }

    @Test
    void documentationGovernanceRefusesAPlainMember() {
        givenPlatformRoles("ADMIN");
        givenMembership(WorkspaceRole.MEMBER);

        assertThrows(ForbiddenException.class,
                () -> service.requireDocumentationGovernance(userId, workspaceId));
    }

    /**
     * The documentation owner owns the documentation, not the workspace.
     * It must never satisfy a workspace-manager requirement.
     */
    @Test
    void documentationOwnerIsNotAWorkspaceManager() {
        givenPlatformRoles("ADMIN");
        givenMembership(WorkspaceRole.DOCUMENTATION_OWNER);

        assertThrows(ForbiddenException.class,
                () -> service.requireWorkspaceManager(userId, workspaceId));
    }

    // -----------------------------------------------------------------
    // capabilities
    // -----------------------------------------------------------------

    @Test
    void capabilitiesAreEmptyForANonMember() {
        givenPlatformRoles("ADMIN");
        givenNoMembership();

        var caps = service.capabilities(userId, workspaceId);

        assertFalse(caps.member());
        assertFalse(caps.canStaffReview(),
                "membership is required for every review capability");
        assertFalse(caps.canManagerApprove());
        assertTrue(caps.admin(), "the platform role itself is still reported");
    }

    @Test
    void capabilitiesReflectBothAxes() {
        givenPlatformRoles("STAFF");
        givenMembership(WorkspaceRole.MEMBER);
        givenPermissions(PermissionCodes.DOCUMENT_VERSION_REVIEW);

        var caps = service.capabilities(userId, workspaceId);

        assertTrue(caps.member());
        assertTrue(caps.canStaffReview());
        assertFalse(caps.canManagerApprove());
        assertFalse(caps.workspaceManager());
        assertEquals(List.of("STAFF"), caps.platformRoles());
    }

    @Test
    void technicalLeadCapabilitiesCoverBothTiers() {
        givenPlatformRoles("TECHNICAL_LEAD");
        givenMembership(WorkspaceRole.MEMBER);
        givenPermissions(PermissionCodes.DOCUMENT_VERSION_REVIEW,
                PermissionCodes.DOCUMENT_VERSION_APPROVE);

        var caps = service.capabilities(userId, workspaceId);

        assertTrue(caps.canStaffReview());
        assertTrue(caps.canManagerApprove());
        assertFalse(caps.workspaceManager(),
                "technical leadership does not imply workspace administration");
        assertFalse(caps.documentationOwner(),
                "technical leadership does not imply workspace documentation ownership");
    }

    /**
     * The documentation owner is reported separately from the workspace
     * manager: it owns the templates, the doc-type workflows and the
     * governance settings, but it does not administer the membership.
     */
    @Test
    void documentationOwnerIsReportedSeparatelyFromTheManager() {
        givenPlatformRoles("DEVELOPER");
        givenMembership(WorkspaceRole.DOCUMENTATION_OWNER);

        var caps = service.capabilities(userId, workspaceId);

        assertTrue(caps.member());
        assertTrue(caps.documentationOwner());
        assertFalse(caps.workspaceManager());
    }

    /**
     * The review capability now comes from the permission matrix, not
     * from the platform role list. A Staff member whose
     * {@code DOCUMENT_VERSION_REVIEW} grant was revoked must not see a
     * review queue — that was the whole point of moving the guard off
     * the hard-coded role list.
     */
    @Test
    void reviewCapabilityFollowsThePermissionMatrix() {
        givenPlatformRoles("STAFF");
        givenMembership(WorkspaceRole.MEMBER);
        // No permission granted.

        var caps = service.capabilities(userId, workspaceId);

        assertFalse(caps.canStaffReview(),
                "the platform role alone no longer grants the review capability");
        assertFalse(caps.canManagerApprove());
    }

    /**
     * ADMIN keeps its blanket bypass even if the matrix was edited by
     * hand — an operator must never be able to lock the platform out of
     * its own review surface.
     */
    @Test
    void adminBypassesThePermissionMatrix() {
        givenPlatformRoles("ADMIN");
        givenMembership(WorkspaceRole.MEMBER);
        // No permission granted.

        var caps = service.capabilities(userId, workspaceId);

        assertTrue(caps.canStaffReview());
        assertTrue(caps.canManagerApprove());
    }

    // -----------------------------------------------------------------
    // drift resolution
    // -----------------------------------------------------------------

    /**
     * Resolving a drift alert is a content judgement, so it needs the
     * {@code DRIFT_RESOLVE} capability from the matrix.
     */
    @Test
    void driftResolutionFollowsThePermissionMatrix() {
        givenPlatformRoles("STAFF");
        givenMembership(WorkspaceRole.MEMBER);

        assertThrows(ForbiddenException.class,
                () -> service.requireDriftResolver(userId, workspaceId));
    }

    @Test
    void aHolderOfTheCapabilityMayResolveDriftAlerts() {
        givenPlatformRoles("STAFF");
        givenMembership(WorkspaceRole.MEMBER);
        givenPermissions(PermissionCodes.DRIFT_RESOLVE);

        assertDoesNotThrow(() -> service.requireDriftResolver(userId, workspaceId));
    }

    /**
     * The capability alone is not enough: the AND rule still applies, so
     * a reviewer who is not a member of the workspace cannot resolve an
     * alert in it.
     */
    @Test
    void theDriftCapabilityDoesNotReplaceMembership() {
        givenPlatformRoles("STAFF");
        givenNoMembership();
        givenPermissions(PermissionCodes.DRIFT_RESOLVE);

        assertThrows(ForbiddenException.class,
                () -> service.requireDriftResolver(userId, workspaceId));
    }

    /**
     * ADMIN keeps its blanket bypass — an operator must never be locked
     * out of the platform's own review surface.
     */
    @Test
    void adminBypassesTheDriftCapability() {
        givenPlatformRoles("ADMIN");
        givenMembership(WorkspaceRole.MEMBER);

        assertDoesNotThrow(() -> service.requireDriftResolver(userId, workspaceId));
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private void givenPlatformRoles(String... roles) {
        when(roleRepository.findActiveRoleCodesByUserId(userId)).thenReturn(List.of(roles));
    }

    /**
     * Grant the given permission codes to the user.
     *
     * <p>The production call is a varargs method. Mockito expands the
     * varargs into separate invocation arguments, so a two-code call
     * arrives as {@code [userId, "CODE_A", "CODE_B"]} rather than as
     * {@code [userId, String[]]}. The answer therefore flattens
     * everything after the user id, whatever shape it arrives in.
     */
    private void givenPermissions(String... codes) {
        granted.clear();
        granted.addAll(List.of(codes));
    }

    private void givenMembership(WorkspaceRole role) {
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(true);
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId))
                .thenReturn(Optional.of(new WorkspaceMember(
                        workspaceId, userId, role)));
    }

    private void givenNoMembership() {
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, userId)).thenReturn(false);
        when(memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId))
                .thenReturn(Optional.empty());
    }
}
