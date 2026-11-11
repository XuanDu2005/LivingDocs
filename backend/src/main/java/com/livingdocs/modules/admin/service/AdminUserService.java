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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The lifecycle half of the administrator's user management: creating
 * an account, resetting its password, and deleting it.
 *
 * <p>The read/toggle half lives in {@link AdminService}; the role
 * catalogue and the role assignments live in {@link RoleService}. This
 * service deliberately calls into {@link RoleService} rather than
 * touching the assignment table itself, so there is exactly one place
 * that knows how a role is granted, revoked and audited.
 *
 * <p>Every operation here is a governance action, so every one of them
 * records an audit entry. The audit entry deliberately never carries
 * the password: it records the fact that the credential was replaced,
 * not the credential.
 */
@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final RoleService roleService;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final DocumentRepository documentRepository;
    private final RepositoryRepository repositoryRepository;
    private final DocTemplateRepository templateRepository;
    private final DocumentReviewRepository reviewRepository;
    private final IndexJobRepository indexJobRepository;
    private final AuditLogService auditLogService;
    private final RefreshTokenService refreshTokenService;
    private final AvatarStorageService avatarStorageService;
    private final PasswordEncoder passwordEncoder;

    public AdminUserService(UserRepository userRepository,
                            UserRoleAssignmentRepository assignmentRepository,
                            RoleService roleService,
                            WorkspaceRepository workspaceRepository,
                            WorkspaceMemberRepository memberRepository,
                            DocumentRepository documentRepository,
                            RepositoryRepository repositoryRepository,
                            DocTemplateRepository templateRepository,
                            DocumentReviewRepository reviewRepository,
                            IndexJobRepository indexJobRepository,
                            AuditLogService auditLogService,
                            RefreshTokenService refreshTokenService,
                            AvatarStorageService avatarStorageService,
                            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.roleService = roleService;
        this.workspaceRepository = workspaceRepository;
        this.memberRepository = memberRepository;
        this.documentRepository = documentRepository;
        this.repositoryRepository = repositoryRepository;
        this.templateRepository = templateRepository;
        this.reviewRepository = reviewRepository;
        this.indexJobRepository = indexJobRepository;
        this.auditLogService = auditLogService;
        this.refreshTokenService = refreshTokenService;
        this.avatarStorageService = avatarStorageService;
        this.passwordEncoder = passwordEncoder;
    }

    // -----------------------------------------------------------------
    // Create
    // -----------------------------------------------------------------

    /**
     * Create a platform account on behalf of an administrator.
     *
     * <p>The account is created with a temporary password and lands in
     * the "must change password" state. The administrator chose the
     * credential, so the account owner still has to replace it with one
     * only they know — the same rule the self-service "forgot password"
     * flow applies.
     *
     * <p>The roles are granted through {@link RoleService#replaceRoles},
     * so the role assignment is audited exactly like a role change made
     * on an existing account.
     */
    @Transactional
    @CacheEvict(cacheNames = "user-permissions", allEntries = true)
    public UserWithRolesResponse create(UUID actorId, CreateUserRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists");
        }

        User user = new User(email, req.displayName().trim(),
                passwordEncoder.encode(req.temporaryPassword()));
        // The account is usable immediately — an administrator vouches
        // for the address — but the password is a placeholder.
        user.markEmailVerified();
        user.setMustChangePassword(true);
        user.setEnabled(req.enabled() == null || req.enabled());
        User saved = userRepository.save(user);

        roleService.replaceRoles(actorId, saved.getId(), req.roles());

        auditLogService.record(actorId, "ADMIN", "user.created",
                "user", saved.getId().toString(), null,
                Map.of(
                        "email", saved.getEmail(),
                        "displayName", saved.getDisplayName(),
                        "roles", req.roles(),
                        "mustChangePassword", true,
                        "enabled", saved.isEnabled()
                ));

        return toResponse(saved);
    }

    // -----------------------------------------------------------------
    // Reset password
    // -----------------------------------------------------------------

    /**
     * Replace a user's password with one the administrator chose.
     *
     * <p>Every active session of that user is revoked: the sessions were
     * established with the old credential, and leaving them alive would
     * mean a password reset does not actually lock out whoever had the
     * old one.
     *
     * <p>The account lands in the "must change password" state, so the
     * new credential is only good enough to reach the login screen.
     */
    @Transactional
    public UserWithRolesResponse resetPassword(UUID actorId,
                                               UUID userId,
                                               ResetUserPasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.setMustChangePassword(true);
        userRepository.save(user);

        // The sessions established with the old password are no longer
        // trustworthy.
        int revokedSessions = refreshTokenService.revokeAllForUser(userId);

        auditLogService.record(actorId, "ADMIN", "user.password.reset",
                "user", userId.toString(), null,
                Map.of(
                        "email", user.getEmail(),
                        "mustChangePassword", true,
                        "sessionsRevoked", revokedSessions
                ));

        return toResponse(user);
    }

    // -----------------------------------------------------------------
    // Delete
    // -----------------------------------------------------------------

    /**
     * Delete a platform account.
     *
     * <p>The account has to be one the administrator is not about to
     * lock themselves out of, and it must not still own content: the
     * {@code workspaces.owner_id}, {@code documents.owner_id},
     * {@code doc_templates.created_by} and
     * {@code document_reviews.reviewer_user_id} foreign keys are
     * {@code ON DELETE RESTRICT}, so the deletion would fail at the
     * database level with a message nobody understands. The checks
     * happen here instead, so the administrator gets a sentence that
     * says what to do.
     *
     * <p>Everything that hangs off the account cascades (workspace
     * memberships, role assignments, notifications, refresh tokens).
     */
    @Transactional
    public void delete(UUID actorId, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        if (user.getId().equals(actorId)) {
            throw new ForbiddenException("You cannot delete your own account.");
        }

        requireTransferable(userId);

        long memberships = memberRepository.countByUserId(userId);
        String email = user.getEmail();
        String displayName = user.getDisplayName();

        // The audit entry is written *before* the row disappears:
        // `audit_logs.actor_user_id` is ON DELETE SET NULL, so an entry
        // written after the delete would lose its actor.
        auditLogService.record(actorId, "ADMIN", "user.deleted",
                "user", userId.toString(), null,
                Map.of(
                        "email", email,
                        "displayName", displayName == null ? "" : displayName,
                        "membershipsRemoved", memberships,
                        "rolesRevoked", assignmentRepository.findAllByUserId(userId).size()
                ));

        // The avatar file is not part of the transaction — it lives on
        // disk — so it is cleaned up after the row is gone. A failure
        // there is a leftover file, not a broken request.
        String avatarKey = user.getAvatarUrl();

        userRepository.delete(user);

        if (avatarKey != null && !avatarKey.isBlank()) {
            avatarStorageService.delete(avatarKey);
        }
    }

    /**
     * Refuse the deletion while the account still owns content that
     * would block it at the database level.
     *
     * <p>Each of these foreign keys is {@code ON DELETE RESTRICT}: the
     * account cannot go away while the row that points at it stays.
     */
    private void requireTransferable(UUID userId) {
        long ownedWorkspaces = workspaceRepository.countByOwnerId(userId);
        if (ownedWorkspaces > 0) {
            throw new ConflictException(
                    "This account still owns " + ownedWorkspaces
                            + " workspace(s). Transfer ownership before deleting it.");
        }

        long ownedDocuments = documentRepository.countByOwnerId(userId);
        if (ownedDocuments > 0) {
            throw new ConflictException(
                    "This account still owns " + ownedDocuments
                            + " document(s). Transfer ownership before deleting it.");
        }

        long createdTemplates = templateRepository.countByCreatedBy(userId);
        if (createdTemplates > 0) {
            throw new ConflictException(
                    "This account still created " + createdTemplates
                            + " template(s). Reassign them before deleting it.");
        }

        long writtenReviews = reviewRepository.countByReviewerUserId(userId);
        if (writtenReviews > 0) {
            throw new ConflictException(
                    "This account still has " + writtenReviews
                            + " review(s) on record and cannot be deleted.");
        }

        long connectedRepositories = repositoryRepository.countByConnectedBy(userId);
        if (connectedRepositories > 0) {
            throw new ConflictException(
                    "This account still connected " + connectedRepositories
                            + " repository(ies). Unlink them before deleting it.");
        }

        long enqueuedJobs = indexJobRepository.countByCreatedBy(userId);
        if (enqueuedJobs > 0) {
            throw new ConflictException(
                    "This account still enqueued " + enqueuedJobs
                            + " indexing job(s) and cannot be deleted.");
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private UserWithRolesResponse toResponse(User user) {
        return new UserWithRolesResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                avatarStorageService.publicUrl(user.getAvatarUrl()),
                user.isEnabled(),
                user.isMustChangePassword(),
                user.getCreatedAt(),
                assignmentRepository.findActiveRoleCodesByUserId(user.getId())
        );
    }
}
