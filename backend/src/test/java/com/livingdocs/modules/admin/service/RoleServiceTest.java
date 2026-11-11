package com.livingdocs.modules.admin.service;

import com.livingdocs.modules.admin.model.Role;
import com.livingdocs.modules.admin.model.UserRoleAssignment;
import com.livingdocs.modules.admin.repository.RoleRepository;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.user.service.AvatarStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.annotation.CacheEvict;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RoleService#replaceRoles}.
 *
 * <p>Two behaviours are covered, both of which used to be broken:
 *
 * <ol>
 *   <li>the role change must evict the {@code user-permissions} cache,
 *       otherwise the effective permission set of the affected user stays
 *       stale for the whole cache TTL;</li>
 *   <li>a role that was revoked earlier must be re-activated rather
 *       than granted again as a second row, because the table is a
 *       soft-delete log and the (user, role) pair is unique among the
 *       active rows.</li>
 * </ol>
 */
class RoleServiceTest {

    private RoleService service;
    private RoleRepository roleRepository;
    private UserRoleAssignmentRepository assignmentRepository;
    private UserRepository userRepository;
    private AuditLogService auditLogService;

    private UUID userId;
    private UUID developerRoleId;
    private UUID staffRoleId;

    @BeforeEach
    void setUp() {
        roleRepository = mock(RoleRepository.class);
        assignmentRepository = mock(UserRoleAssignmentRepository.class);
        userRepository = mock(UserRepository.class);
        auditLogService = mock(AuditLogService.class);

        service = new RoleService(roleRepository, assignmentRepository,
                userRepository, auditLogService, mock(AvatarStorageService.class));

        userId = UUID.randomUUID();
        developerRoleId = UUID.randomUUID();
        staffRoleId = UUID.randomUUID();

        User user = new User("probe@example.test", "Probe", "hash");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Role developer = role("DEVELOPER", developerRoleId);
        Role staff = role("STAFF", staffRoleId);

        when(roleRepository.findByCode("DEVELOPER")).thenReturn(Optional.of(developer));
        when(roleRepository.findByCode("STAFF")).thenReturn(Optional.of(staff));
        when(roleRepository.findById(developerRoleId)).thenReturn(Optional.of(developer));
        when(roleRepository.findById(staffRoleId)).thenReturn(Optional.of(staff));
    }

    // -----------------------------------------------------------------

    @Test
    void evictsThePermissionCacheSoTheChangeTakesEffectAtOnce() throws Exception {
        when(assignmentRepository.findActiveByUserId(userId)).thenReturn(List.of());
        when(assignmentRepository.findByUserIdAndRoleId(eq(userId), any())).thenReturn(Optional.empty());
        when(assignmentRepository.findActiveRoleCodesByUserId(userId)).thenReturn(List.of("STAFF"));

        service.replaceRoles(UUID.randomUUID(), userId, List.of("STAFF"));

        Method method = RoleService.class.getMethod("replaceRoles",
                UUID.class, UUID.class, List.class);
        CacheEvict evict = method.getAnnotation(CacheEvict.class);
        assertNotNull(evict, "replaceRoles must evict a cache");
        assertTrue(List.of(evict.cacheNames()).contains("user-permissions"),
                "the evicted cache must be the one holding the effective permissions");
        assertTrue(evict.allEntries(),
                "every user must be evicted — one user's role change affects only that user, "
                        + "but the cache is shared and the entries are keyed by user id");
    }

    @Test
    void reActivatesARevokedRoleInsteadOfInsertingASecondRow() {
        // The user once held STAFF and it was revoked; the row is still
        // there, carrying the audit trail.
        UserRoleAssignment revoked = new UserRoleAssignment(userId, staffRoleId, UUID.randomUUID());
        revoked.revoke();

        when(assignmentRepository.findActiveByUserId(userId)).thenReturn(List.of());
        when(assignmentRepository.findByUserIdAndRoleId(userId, staffRoleId)).thenReturn(Optional.of(revoked));
        when(assignmentRepository.findActiveRoleCodesByUserId(userId)).thenReturn(List.of("STAFF"));

        service.replaceRoles(UUID.randomUUID(), userId, List.of("STAFF"));

        ArgumentCaptor<UserRoleAssignment> saved =
                ArgumentCaptor.forClass(UserRoleAssignment.class);
        verify(assignmentRepository).save(saved.capture());

        UserRoleAssignment result = saved.getValue();
        assertEquals(staffRoleId, result.getRoleId());
        assertNull(result.getRevokedAt(), "the assignment must be active again");
        assertTrue(result.isActive());
        // The original row was reused, not replaced by a new one.
        assertSame(revoked, result,
                "the revoked row must be re-activated instead of a second row being inserted");
    }

    @Test
    void grantsARoleThatWasNeverHeldBefore() {
        when(assignmentRepository.findActiveByUserId(userId)).thenReturn(List.of());
        when(assignmentRepository.findByUserIdAndRoleId(eq(userId), any())).thenReturn(Optional.empty());
        when(assignmentRepository.findActiveRoleCodesByUserId(userId)).thenReturn(List.of("STAFF"));

        service.replaceRoles(UUID.randomUUID(), userId, List.of("STAFF"));

        ArgumentCaptor<UserRoleAssignment> saved =
                ArgumentCaptor.forClass(UserRoleAssignment.class);
        verify(assignmentRepository).save(saved.capture());
        assertEquals(staffRoleId, saved.getValue().getRoleId());
        assertTrue(saved.getValue().isActive());
    }

    @Test
    void revokesRolesThatAreNoLongerRequested() {
        UserRoleAssignment developerAssignment =
                new UserRoleAssignment(userId, developerRoleId, UUID.randomUUID());

        when(assignmentRepository.findActiveByUserId(userId)).thenReturn(List.of(developerAssignment));
        when(assignmentRepository.findByUserIdAndRoleId(eq(userId), any())).thenReturn(Optional.empty());
        when(assignmentRepository.findActiveRoleCodesByUserId(userId)).thenReturn(List.of("STAFF"));

        service.replaceRoles(UUID.randomUUID(), userId, List.of("STAFF"));

        assertFalse(developerAssignment.isActive(),
                "the developer role must be revoked when it is dropped");
        assertNotNull(developerAssignment.getRevokedAt());
    }

    @Test
    void leavesAnAlreadyActiveRoleUntouched() {
        UserRoleAssignment developerAssignment =
                new UserRoleAssignment(userId, developerRoleId, UUID.randomUUID());

        when(assignmentRepository.findActiveByUserId(userId)).thenReturn(List.of(developerAssignment));
        when(assignmentRepository.findByUserIdAndRoleId(eq(userId), any())).thenReturn(Optional.empty());
        when(assignmentRepository.findActiveRoleCodesByUserId(userId)).thenReturn(List.of("DEVELOPER"));

        service.replaceRoles(UUID.randomUUID(), userId, List.of("DEVELOPER"));

        assertTrue(developerAssignment.isActive());
        verify(assignmentRepository, never()).save(any());
    }

    // -----------------------------------------------------------------
    // Role metadata
    // -----------------------------------------------------------------

    /**
     * Editing a role's presentation changes the name, the description
     * and the display order — and nothing else. The role's
     * {@code code} is the identifier that JWTs, audit payloads and
     * permission grants refer to, so it is never touched.
     */
    @Test
    void editingTheMetadataChangesThePresentationOnly() {
        Role staff = new Role(staffRoleId, "STAFF", "Staff (Reviewer)",
                "Reviews documentation changes.", true, 20);
        when(roleRepository.findById(staffRoleId)).thenReturn(Optional.of(staff));

        service.updateRoleMetadata(UUID.randomUUID(), staffRoleId,
                "Senior Reviewer", "Reviews and escalates.", 25);

        assertEquals("Senior Reviewer", staff.getName());
        assertEquals("Reviews and escalates.", staff.getDescription());
        assertEquals(25, staff.getDisplayOrder());
        assertEquals("STAFF", staff.getCode(),
                "the role code is the immutable identifier — it must not change");
    }

    /**
     * The edit is audited, and the entry records both the previous and
     * the new presentation so the trail says what changed.
     */
    @Test
    void editingTheMetadataIsAudited() {
        Role staff = new Role(staffRoleId, "STAFF", "Staff (Reviewer)",
                "Reviews documentation changes.", true, 20);
        when(roleRepository.findById(staffRoleId)).thenReturn(Optional.of(staff));

        UUID actorId = UUID.randomUUID();
        service.updateRoleMetadata(actorId, staffRoleId, "Senior Reviewer", null, 25);

        ArgumentCaptor<java.util.Map<String, ?>> payload =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditLogService).record(eq(actorId), eq("ADMIN"), eq("role.metadata.update"),
                eq("role"), eq(staffRoleId.toString()), eq(null), payload.capture());
        assertEquals("Staff (Reviewer)", payload.getValue().get("previousName"));
        assertEquals("Senior Reviewer", payload.getValue().get("name"));
        assertEquals(20, payload.getValue().get("previousDisplayOrder"));
        assertEquals(25, payload.getValue().get("displayOrder"));
    }

    /**
     * A blank name is ignored rather than blanking the role — an empty
     * display name would leave the role picker with an unlabelled
     * entry.
     */
    @Test
    void aBlankNameIsIgnored() {
        Role staff = new Role(staffRoleId, "STAFF", "Staff (Reviewer)",
                "Reviews documentation changes.", true, 20);
        when(roleRepository.findById(staffRoleId)).thenReturn(Optional.of(staff));

        service.updateRoleMetadata(UUID.randomUUID(), staffRoleId, "   ", null, null);

        assertEquals("Staff (Reviewer)", staff.getName());
    }

    // -----------------------------------------------------------------

    private static Role role(String code, UUID id) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(id);
        when(role.getCode()).thenReturn(code);
        return role;
    }
}