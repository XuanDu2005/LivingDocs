package com.livingdocs.modules.workspace.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.workspace.model.WorkspaceCiToken;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.repository.WorkspaceCiTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies the long-lived CI tokens.
 *
 * <p>The merge gate is enforced by the CI job calling back into the
 * platform: "this pull request wants to merge — may it?". That call
 * needs a credential, and a workflow has no user session to present a
 * JWT from. This service hands out one token per workspace and resolves
 * a presented token back to the workspace it belongs to.
 *
 * <h2>Why the token is hashed</h2>
 *
 * <p>The platform never needs to read a CI token back — it only has to
 * verify one. The token is therefore stored as a SHA-256 hash and the
 * plaintext is shown to the manager exactly once, at creation time.
 * A stolen database row yields no usable credential.
 *
 * <p>This differs deliberately from the GitHub OAuth token and the AI
 * provider key, which <em>are</em> retrievable because the platform has
 * to present them to a third party. Those are encrypted; this one is
 * hashed.
 *
 * <h2>Scope</h2>
 *
 * <p>A token authenticates exactly one workspace. The resolution never
 * falls back to "the token is valid for any workspace" — a token that
 * does not resolve is rejected, not ignored.
 */
@Service
public class WorkspaceCiTokenService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkspaceCiTokenService.class);

    /** Number of random bytes in a generated token. */
    private static final int TOKEN_BYTES = 32;

    private final WorkspaceCiTokenRepository repository;
    private final WorkspaceService workspaceService;
    private final AuditLogService auditLogService;
    private final SecureRandom random = new SecureRandom();

    public WorkspaceCiTokenService(WorkspaceCiTokenRepository repository,
                                   WorkspaceService workspaceService,
                                   AuditLogService auditLogService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.auditLogService = auditLogService;
    }

    /**
     * Whether this workspace already has a CI token.
     *
     * <p>Used by the UI to decide whether to offer "issue" or "rotate".
     */
    @Transactional(readOnly = true)
    public boolean hasToken(UUID workspaceId) {
        return repository.findByWorkspaceId(workspaceId).isPresent();
    }

    /**
     * Issue (or rotate) the CI token of a workspace.
     *
     * <p>Requires the workspace {@code MANAGER} role: a CI token is a
     * credential that lets a job act on the workspace, so handing it out
     * is a workspace-administration decision.
     *
     * <p>The plaintext is returned exactly once — it cannot be recovered
     * later, because only its hash is stored.
     *
     * @return the plaintext token
     */
    @Transactional
    public IssuedToken issue(UUID actorId, UUID workspaceId) {
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);

        boolean hadPrevious = repository.findByWorkspaceId(workspaceId).isPresent();

        String plaintext = generateToken();
        String hash = hash(plaintext);

        WorkspaceCiToken row = repository.findByWorkspaceId(workspaceId)
                .orElseGet(() -> new WorkspaceCiToken(workspaceId, hash));
        // A rotation replaces the hash in place, which invalidates the
        // secret that GitHub currently holds.
        row.setTokenHash(hash);
        repository.save(row);

        auditLogService.record(actorId, "MANAGER",
                hadPrevious ? "workspace.ci_token.rotate" : "workspace.ci_token.issue",
                "workspace_ci_token",
                workspaceId.toString(),
                workspaceId,
                Map.of("rotated", hadPrevious));

        log.info("Workspace {} CI token {} (actor={})",
                workspaceId, hadPrevious ? "rotated" : "issued", actorId);

        return new IssuedToken(plaintext, row.getIssuedAt());
    }

    /**
     * Resolve a presented CI token to the workspace it belongs to.
     *
     * <p>Returns {@link Optional#empty()} when the token is unknown. The
     * lookup is a single indexed read of the hash — no workspace is
     * scanned, and no token is ever compared in plaintext.
     */
    @Transactional
    public Optional<UUID> resolveWorkspace(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }
        Optional<WorkspaceCiToken> row = repository.findByTokenHash(hash(presentedToken.trim()));
        if (row.isEmpty()) {
            return Optional.empty();
        }
        WorkspaceCiToken token = row.get();
        token.setLastUsedAt(OffsetDateTime.now());
        repository.save(token);
        return Optional.of(token.getWorkspaceId());
    }

    /**
     * Drop the CI token of a workspace.
     *
     * <p>Requires the workspace {@code MANAGER} role.
     */
    @Transactional
    public void revoke(UUID actorId, UUID workspaceId) {
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MANAGER);

        WorkspaceCiToken row = repository.findByWorkspaceId(workspaceId)
                .orElseThrow(() -> new BadRequestException(
                        "This workspace has no CI token to revoke"));

        repository.delete(row);

        auditLogService.record(actorId, "MANAGER",
                "workspace.ci_token.revoke",
                "workspace_ci_token",
                workspaceId.toString(),
                workspaceId,
                Map.of());

        log.info("Workspace {} CI token revoked (actor={})", workspaceId, actorId);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return "ldci_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * A freshly issued token. The plaintext is only ever present in this
     * object — it is never persisted.
     */
    public record IssuedToken(String token, OffsetDateTime issuedAt) {
    }
}
