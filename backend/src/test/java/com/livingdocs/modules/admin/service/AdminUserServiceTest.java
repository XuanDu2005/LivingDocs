package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.admin.dto.CreateUserRequest;
import com.livingdocs.modules.admin.dto.ResetUserPasswordRequest;
import com.livingdocs.modules.admin.dto.UserWithRolesResponse;
import com.livingdocs.modules.admin.repository.UserRoleAssignmentRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.ai.indexing.IndexJobRepository;
import com.livingdocs.modules.auth.service.RefreshTokenService;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.review.repository.DocumentReviewRepository;
import com.livingdocs.modules.template.repository.DocTemplateRepository;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.user.service.AvatarStorageService;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules of the administrator's user lifecycle: creating an
 * account, resetting its password, and deleting it.
 *
 * <p>Three properties matter most, and all three are about not
 * letting a temporary credential become a permanent one:
 *
 * <ol>
 *   <li>an account created by an administrator has to replace the
 *       password before it can do anything;</li>
 *   <li>a password reset revokes the sessions that were established
 *       with the old credential;</li>
 *   <li>an account that still owns content cannot be deleted, because
 *       the foreign keys that point at it would refuse the deletion at
 *       the database level with a message nobody understands.</li>
 * </ol>
 */
class AdminUserServiceTest {

    private UserRepository userRepository;
    private UserRoleAssignmentRepository assignmentRepository;
    private RoleService roleService;
    private WorkspaceRepository workspaceRepository;
    private WorkspaceMemberRepository memberRepository;
    private DocumentRepository documentRepository;
    private RepositoryRepository repositoryRepository;
    private DocTemplateRepository templateRepository;
    private DocumentReviewRepository reviewRepository;
    private IndexJobRepository indexJobRepository;
    private AuditLogService auditLogService;
    private RefreshTokenService refreshTokenService;
    private AvatarStorageService avatarStorageService;
    private PasswordEncoder passwordEncoder;

    private AdminUserService service;

    private UUID actorId;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        assignmentRepository = mock(UserRoleAssignmentRepository.class);
        roleService = mock(RoleService.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        memberRepository = mock(WorkspaceMemberRepository.class);
        documentRepository = mock(DocumentRepository.class);
        repositoryRepository = mock(RepositoryRepository.class);
        templateRepository = mock(DocTemplateRepository.class);
        reviewRepository = mock(DocumentReviewRepository.class);
        indexJobRepository = mock(IndexJobRepository.class);
        auditLogService = mock(AuditLogService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        avatarStorageService = mock(AvatarStorageService.class);
        passwordEncoder = mock(PasswordEncoder.class);

        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "hashed:" + inv.getArgument(0));

        service = new AdminUserService(userRepository, assignmentRepository, roleService,
                workspaceRepository, memberRepository, documentRepository, repositoryRepository,
                templateRepository, reviewRepository, indexJobRepository, auditLogService,
                refreshTokenService, avatarStorageService, passwordEncoder);

        actorId = UUID.randomUUID();
    }

    // -----------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------

    /**
     * The repository mock: nothing exists yet, and a saved user gets an
     * id (the real database assigns it).
     */
    private void givenNoExistingUser() {
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setEmailForTest(UUID.randomUUID());
            }
            return u;
        });
    }

    /**
     * A new account is created with the administrator's temporary
     * password, and it is flagged as "must change password" — the
     * temporary credential is a handover device, not a real one.
     */
    @Test
    void aCreatedAccountHasToReplaceItsTemporaryPassword() {
        givenNoExistingUser();

        UserWithRolesResponse created = service.create(actorId, new CreateUserRequest(
                "New@Example.test", "New Person", "temporary-password",
                List.of("DEVELOPER"), null, null));

        assertEquals("new@example.test", created.email());
        assertTrue(created.mustChangePassword(),
                "the response has to tell the administrator the account still "
                        + "carries a temporary credential");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertTrue(saved.getValue().isMustChangePassword(),
                "the account must be flagged as needing a password change");
        assertTrue(saved.getValue().isEmailVerified(),
                "an administrator vouches for the address — the account is usable at once");
    }

    /**
     * The email is stored lower-cased, because the login lookup is
     * case-insensitive and the unique index is on the stored value.
     */
    @Test
    void theEmailIsStoredLowerCased() {
        givenNoExistingUser();

        service.create(actorId, new CreateUserRequest(
                "New@Example.test", "New Person", "temporary-password",
                List.of("DEVELOPER"), null, null));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertEquals("new@example.test", saved.getValue().getEmail());
    }

    /**
     * A duplicate address is a conflict, not a second account.
     */
    @Test
    void aDuplicateEmailIsRefused() {
        when(userRepository.existsByEmailIgnoreCase("taken@example.test")).thenReturn(true);

        assertThrows(ConflictException.class, () -> service.create(actorId, new CreateUserRequest(
                "taken@example.test", "Someone", "temporary-password",
                List.of("DEVELOPER"), null, null)));

        verify(userRepository, never()).save(any());
    }

    /**
     * The roles are granted through the role service, so the assignment
     * is audited exactly like a role change on an existing account.
     */
    @Test
    void theRolesAreGrantedThroughTheRoleService() {
        givenNoExistingUser();

        service.create(actorId, new CreateUserRequest(
                "new@example.test", "New Person", "temporary-password",
                List.of("DEVELOPER", "STAFF"), null, null));

        verify(roleService).replaceRoles(eq(actorId), any(UUID.class), eq(List.of("DEVELOPER", "STAFF")));
    }

    /**
     * The creation is audited. The audit entry never carries the
     * password — it records that a credential was issued, not the
     * credential.
     */
    @Test
    void theCreationIsAuditedWithoutThePassword() {
        givenNoExistingUser();

        service.create(actorId, new CreateUserRequest(
                "new@example.test", "New Person", "temporary-password",
                List.of("DEVELOPER"), null, null));

        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(eq(actorId), anyString(), eq("user.created"),
                eq("user"), anyString(), isNull(), payload.capture());
        assertFalse(payload.getValue().containsKey("temporaryPassword"));
        assertFalse(payload.getValue().containsKey("password"));
        assertEquals("new@example.test", payload.getValue().get("email"));
    }

    // -----------------------------------------------------------------
    // Reset password
    // -----------------------------------------------------------------

    /**
     * A reset replaces the hash and puts the account back into the
     * "must change password" state: the administrator chose the
     * credential, so the owner still has to replace it.
     */
    @Test
    void aResetPasswordForcesTheOwnerToReplaceTheCredential() {
        UUID userId = UUID.randomUUID();
        User user = new User("someone@example.test", "Someone", "old-hash");
        user.setEmailForTest(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(assignmentRepository.findActiveRoleCodesByUserId(userId)).thenReturn(List.of());

        service.resetPassword(actorId, userId,
                new ResetUserPasswordRequest("another-password"));

        assertTrue(user.isMustChangePassword());
        assertEquals("hashed:another-password", user.getPasswordHash());
    }

    /**
     * Every session established with the old credential is revoked —
     * otherwise a password reset does not lock out whoever had the old
     * one.
     */
    @Test
    void aResetPasswordRevokesTheExistingSessions() {
        UUID userId = UUID.randomUUID();
        User user = new User("someone@example.test", "Someone", "old-hash");
        user.setEmailForTest(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(refreshTokenService.revokeAllForUser(userId)).thenReturn(3);

        service.resetPassword(actorId, userId,
                new ResetUserPasswordRequest("another-password"));

        verify(refreshTokenService).revokeAllForUser(userId);
    }

    /**
     * The reset is audited, again without the credential.
     */
    @Test
    void theResetIsAuditedWithoutThePassword() {
        UUID userId = UUID.randomUUID();
        User user = new User("someone@example.test", "Someone", "old-hash");
        user.setEmailForTest(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.resetPassword(actorId, userId,
                new ResetUserPasswordRequest("another-password"));

        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(eq(actorId), anyString(), eq("user.password.reset"),
                eq("user"), eq(userId.toString()), isNull(), payload.capture());
        assertFalse(payload.getValue().containsKey("newPassword"));
        assertFalse(payload.getValue().containsKey("password"));
    }

    /**
     * Resetting the password of an account that does not exist is a
     * 404, not a silent success.
     */
    @Test
    void resettingAnUnknownAccountIsRefused() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.resetPassword(actorId, userId,
                new ResetUserPasswordRequest("another-password")));
    }

    // -----------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------

    /**
     * An administrator cannot delete their own account — that would
     * lock them out of the platform.
     */
    @Test
    void anAdministratorCannotDeleteTheirOwnAccount() {
        UUID userId = UUID.randomUUID();
        User user = new User("admin@example.test", "Admin", "hash");
        user.setEmailForTest(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThrows(ForbiddenException.class, () -> service.delete(userId, userId));

        verify(userRepository, never()).delete(any());
    }

    /**
     * An account that still owns a workspace cannot be deleted: the
     * {@code workspaces.owner_id} foreign key is ON DELETE RESTRICT.
     * The refusal happens in the service so the administrator gets a
     * sentence that says what to do.
     */
    @Test
    void anAccountThatOwnsAWorkspaceCannotBeDeleted() {
        UUID userId = UUID.randomUUID();
        User user = new User("owner@example.test", "Owner", "hash");
        user.setEmailForTest(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(workspaceRepository.countByOwnerId(userId)).thenReturn(2L);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.delete(actorId, userId));
        assertTrue(ex.getMessage().contains("2 workspace(s)"),
                "the message names the number of workspaces: " + ex.getMessage());

        verify(userRepository, never()).delete(any());
    }

    /**
     * The same holds for the documents the account owns.
     */
    @Test
    void anAccountThatOwnsADocumentCannotBeDeleted() {
        UUID userId = UUID.randomUUID();
        User user = new User("owner@example.test", "Owner", "hash");
        user.setEmailForTest(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(documentRepository.countByOwnerId(userId)).thenReturn(5L);

        assertThrows(ConflictException.class, () -> service.delete(actorId, userId));

        verify(userRepository, never()).delete(any());
    }

    /**
     * The reviews the account wrote stay on record, so the account
     * cannot be deleted either.
     */
    @Test
    void anAccountWithReviewsOnRecordCannotBeDeleted() {
        UUID userId = UUID.randomUUID();
        User user = new User("reviewer@example.test", "Reviewer", "hash");
        user.setEmailForTest(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(reviewRepository.countByReviewerUserId(userId)).thenReturn(1L);

        assertThrows(ConflictException.class, () -> service.delete(actorId, userId));

        verify(userRepository, never()).delete(any());
    }

    /**
     * A plain account is deleted, and the deletion is audited *before*
     * the row disappears — the audit entry's actor would be lost
     * otherwise.
     */
    @Test
    void anAccountWithoutOwnedContentIsDeleted() {
        UUID userId = UUID.randomUUID();
        User user = new User("someone@example.test", "Someone", "hash");
        user.setEmailForTest(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(memberRepository.countByUserId(userId)).thenReturn(3L);
        when(assignmentRepository.findAllByUserId(userId)).thenReturn(List.of());

        service.delete(actorId, userId);

        verify(userRepository).delete(user);

        ArgumentCaptor<Map<String, ?>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditLogService).record(eq(actorId), anyString(), eq("user.deleted"),
                eq("user"), eq(userId.toString()), isNull(), payload.capture());
        assertEquals("someone@example.test", payload.getValue().get("email"));
        assertEquals(3L, payload.getValue().get("membershipsRemoved"));
    }

    /**
     * Deleting an account that does not exist is a 404.
     */
    @Test
    void deletingAnUnknownAccountIsRefused() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.delete(actorId, userId));
    }

    /**
     * The avatar file is cleaned up with the account — it lives on
     * disk, so it does not disappear with the row.
     */
    @Test
    void theAvatarIsCleanedUpWithTheAccount() {
        UUID userId = UUID.randomUUID();
        User user = new User("someone@example.test", "Someone", "hash");
        user.setEmailForTest(userId);
        user.setAvatarUrl("avatars/" + userId + "/avatar.png");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        service.delete(actorId, userId);

        verify(avatarStorageService).delete("avatars/" + userId + "/avatar.png");
    }
}
