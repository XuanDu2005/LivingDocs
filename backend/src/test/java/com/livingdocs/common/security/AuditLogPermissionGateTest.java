package com.livingdocs.common.security;

import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.modules.admin.repository.RolePermissionRepository;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The permission gate of the audit trail.
 *
 * <p>The MANAGER tier is required to "view the complete audit trail of a
 * document ... before granting approval" and to open the workspace-wide
 * trail. Both surfaces are gated by {@code AUDIT_LOG_VIEW}, so the
 * grant of that code to the MANAGER role is what makes the requirement
 * enforceable at all.
 *
 * <p>It used to be missing: V36 granted the code to ADMIN and
 * TECHNICAL_LEAD only, while its own comment said the trail "stays
 * with MANAGER/ADMIN". The Manager tier therefore got a 403 on the
 * very surface it is required to read. V45 states the invariant.
 *
 * <p>These tests pin the behaviour of the gate end to end — the role
 * assignment, the matrix lookup and the interceptor — because that is
 * where a regression would be invisible: the database grant is
 * asserted by the migration, and a broken wiring would only show up as
 * a 403 in production.
 */
class AuditLogPermissionGateTest {

    private UserRoleAssignmentRepository roleAssignmentRepository;
    private RolePermissionRepository rolePermissionRepository;
    private PermissionService permissionService;
    private PermissionInterceptor interceptor;

    private UUID userId;
    private UUID roleId;

    @BeforeEach
    void setUp() {
        roleAssignmentRepository = mock(UserRoleAssignmentRepository.class);
        rolePermissionRepository = mock(RolePermissionRepository.class);

        permissionService = new PermissionService(
                roleAssignmentRepository, rolePermissionRepository, selfProvider());

        interceptor = new PermissionInterceptor(permissionService);

        userId = UUID.randomUUID();
        roleId = UUID.randomUUID();

        // A user with no role holds no permission — the "no privileges"
        // default that makes the matrix meaningful.
        when(roleAssignmentRepository.findActiveByUserId(any())).thenReturn(List.of());
        when(rolePermissionRepository.findPermissionCodesByRoleId(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * A MANAGER holding the audit capability passes the gate.
     *
     * <p>This is the exact scenario that used to fail: the Manager is
     * a member of the workspace, and the matrix grants it
     * {@code AUDIT_LOG_VIEW}, so the audit surfaces must not answer
     * 403.
     */
    @Test
    void aManagerHoldingTheCapabilityPassesTheGate() {
        givenRoleAssignment();
        givenGrantedPermissions(PermissionCodes.AUDIT_LOG_VIEW);
        authenticate(List.of(AuthorizationService.ROLE_MANAGER));

        assertDoesNotThrow(() -> interceptor.preHandle(
                request(), response(), handler()));
    }

    /**
     * A reader without the capability is refused — the trail is an
     * oversight surface, not a member-level one.
     */
    @Test
    void aReaderWithoutTheCapabilityIsRefused() {
        givenRoleAssignment();
        givenGrantedPermissions(PermissionCodes.DOCUMENT_VERSION_REVIEW);
        authenticate(List.of(AuthorizationService.ROLE_MANAGER));

        assertThrows(ForbiddenException.class, () -> interceptor.preHandle(
                request(), response(), handler()));
    }

    /**
     * A user who holds no role at all holds no permission, so the gate
     * refuses them.
     */
    @Test
    void aUserWithoutAnyRoleIsRefused() {
        authenticate(List.of(AuthorizationService.ROLE_DEVELOPER));

        assertThrows(ForbiddenException.class, () -> interceptor.preHandle(
                request(), response(), handler()));
    }

    /**
     * ADMIN keeps its blanket bypass. The matrix pins its row to "all
     * granted", but the interceptor asserts it explicitly so a
     * mis-seeded matrix can never lock the platform out of its own
     * audit surface.
     */
    @Test
    void anAdminPassesEvenWithoutTheGrant() {
        authenticate(List.of(AuthorizationService.ROLE_ADMIN));

        assertDoesNotThrow(() -> interceptor.preHandle(
                request(), response(), handler()));
    }

    /**
     * The gate only fires where the annotation is present. An endpoint
     * without it keeps whatever guard it already had — the interceptor
     * must not become a blanket deny.
     */
    @Test
    void anEndpointWithoutTheAnnotationIsNotGated() throws NoSuchMethodException {
        authenticate(List.of(AuthorizationService.ROLE_DEVELOPER));

        assertTrue(interceptor.preHandle(request(), response(),
                new HandlerMethod(new AuditSurface(), "noAnnotation")));
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * The caller holds the role whose grants are looked up.
     */
    private void givenRoleAssignment() {
        var assignment = mock(com.livingdocs.modules.admin.model.UserRoleAssignment.class);
        when(assignment.getRoleId()).thenReturn(roleId);
        when(roleAssignmentRepository.findActiveByUserId(userId))
                .thenReturn(List.of(assignment));
    }

    /**
     * The role holds the given permission codes.
     */
    private void givenGrantedPermissions(String... codes) {
        when(rolePermissionRepository.findPermissionCodesByRoleId(roleId))
                .thenReturn(List.of(codes));
    }

    private void authenticate(List<String> roles) {
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, "reader@example.com", "hash", true, roles);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/workspaces/x/audit-logs");
    }

    private MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }

    /**
     * A handler method carrying the {@code AUDIT_LOG_VIEW} annotation —
     * the same annotation the two audit endpoints use.
     */
    private HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(new AuditSurface(), "read");
    }

    /**
     * The self-reference the cached lookup goes through. It is only
     * used by {@code hasAnyPermission}, which the interceptor does not
     * call — but the constructor requires one.
     */
    private ObjectProvider<PermissionService> selfProvider() {
        return new ObjectProvider<>() {
            @Override
            public PermissionService getObject() {
                return permissionService;
            }

            @Override
            public PermissionService getObject(Object... args) {
                return permissionService;
            }

            @Override
            public PermissionService getIfAvailable() {
                return permissionService;
            }

            @Override
            public PermissionService getIfUnique() {
                return permissionService;
            }
        };
    }

    /**
     * A stand-in for the audit controller: one method gated by the
     * audit capability, one that is not.
     */
    static class AuditSurface {
        @RequirePermission({PermissionCodes.AUDIT_LOG_VIEW})
        public void read() {
            // no-op
        }

        public void noAnnotation() {
            // no-op
        }
    }
}
