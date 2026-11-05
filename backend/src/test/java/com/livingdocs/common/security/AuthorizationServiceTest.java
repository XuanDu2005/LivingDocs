package com.livingdocs.common.security;

import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.workspace.model.WorkspaceMember;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        // No permission by default — a user holds nothing until the
        // matrix says otherwise.
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);
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
        when(permissionService.hasAnyPermission(eq(userId), any(String[].class)))
                .thenAnswer(inv -> {
                    for (int i = 1; i < inv.getArguments().length; i++) {
                        Object arg = inv.getArguments()[i];
                        String[] requested = arg instanceof String[] arr
                                ? arr
                                : new String[]{(String) arg};
                        for (String code : requested) {
                            for (String granted : codes) {
                                if (code.equals(granted)) {
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                });
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
