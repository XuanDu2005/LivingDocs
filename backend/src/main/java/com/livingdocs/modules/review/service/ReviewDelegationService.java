package com.livingdocs.modules.review.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionCodes;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.review.dto.CreateDelegationRequest;
import com.livingdocs.modules.review.dto.ReviewDelegationResponse;
import com.livingdocs.modules.review.model.ReviewDelegation;
import com.livingdocs.modules.review.repository.ReviewDelegationRepository;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
/**
 * Review delegation: handing the review or approval work of a workspace
 * to somebody else.
 *
 * <p>This is requirement #12 of the MANAGER role. A Manager who is on
 * leave — or who simply wants a second pair of eyes — had no mechanism
 * to route the work: the Manager tier was notified as a whole, and the
 * delegate was never told anything.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>Who may delegate.</b> Only a platform {@code MANAGER} or
 *       {@code ADMIN}. Delegating is not a review act, it is a
 *       redistribution of oversight, and it belongs to the Manager
 *       tier. It is also gated by its own permission
 *       ({@code REVIEW_DELEGATION_MANAGE}) so an administrator can
 *       revoke it without touching code.</li>
 *   <li><b>Who may receive.</b> The delegate must be a member of the
 *       workspace <em>and</em> hold the permission of the tier being
 *       delegated. Handing the approval work to somebody who cannot
 *       approve would route the work into a black hole.</li>
 *   <li><b>Scope.</b> A delegation covers the whole workspace for one
 *       tier. Narrowing it per document would be a second mechanism for
 *       the same thing.</li>
 * </ul>
 *
 * <h2>What a delegation actually changes</h2>
 * The delegation is not only a record — it is wired into the
 * notification path ({@link DocumentReviewService}): the delegate is
 * notified when work arrives in their tier, and the delegating Manager
 * is not. A delegation nobody is told about would be pointless.
 */
@Service
public class ReviewDelegationService {

    private static final Logger log = LoggerFactory.getLogger(ReviewDelegationService.class);

    /** Tier label: first-tier review. */
    public static final String TIER_STAFF = "STAFF";
    /** Tier label: final approval. */
    public static final String TIER_MANAGER = "MANAGER";

    private final ReviewDelegationRepository delegationRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;

    public ReviewDelegationService(ReviewDelegationRepository delegationRepository,
                                   WorkspaceMemberRepository memberRepository,
                                   UserRepository userRepository,
                                   PermissionService permissionService,
                                   AuthorizationService authorizationService,
                                   AuditLogService auditLogService) {
        this.delegationRepository = delegationRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.permissionService = permissionService;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
    }

    // -----------------------------------------------------------------
    // commands
    // -----------------------------------------------------------------

    /**
     * Delegate the review or approval work of a workspace to somebody
     * else.
     *
     * <p>Enforcement: the actor needs the {@code REVIEW_DELEGATION_MANAGE}
     * permission <em>and</em> the {@code MANAGER}/{@code ADMIN} platform
     * role <em>and</em> workspace membership.
     */
    @Transactional
    public ReviewDelegationResponse create(UUID actorId, UUID workspaceId,
                                           CreateDelegationRequest req) {
        requireDelegator(actorId, workspaceId);

        String tier = normalizeTier(req.tier());
        UUID delegateId = req.toUserId();
        if (delegateId == null) {
            throw new BadRequestException("A delegate is required");
        }
        if (delegateId.equals(actorId)) {
            throw new BadRequestException("You cannot delegate your work to yourself");
        }
        // The delegate has to be in the workspace: the review guards
        // check membership, so a delegate outside the workspace would
        // receive notifications they can never act on.
        if (!memberRepository.existsByWorkspaceIdAndUserId(workspaceId, delegateId)) {
            throw new BadRequestException("The delegate is not a member of this workspace");
        }
        // The delegate has to be able to do the work. Handing the
        // approval tier to somebody without DOCUMENT_VERSION_APPROVE
        // would route the work into a black hole: the notification
        // would arrive and the API would refuse the verdict.
        requireTierPermission(delegateId, tier);

        // Refuse a duplicate with a clear message instead of letting the
        // partial unique index raise a constraint violation.
        delegationRepository
                .findFirstByWorkspaceIdAndToUserIdAndTierAndRevokedAtIsNull(
                        workspaceId, delegateId, tier)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "This person already holds an active " + tier
                                    + " delegation in this workspace");
                });

        String reason = req.reason() != null && !req.reason().isBlank()
                ? req.reason().trim()
                : null;
        ReviewDelegation delegation = delegationRepository.save(
                new ReviewDelegation(workspaceId, actorId, delegateId, tier, reason));

        // The id is assigned by the persistence layer, so it is read
        // defensively: the audit entry must be recorded even when the
        // row has no id yet.
        String delegationId = delegation.getId() != null
                ? delegation.getId().toString()
                : "";
        auditLogService.record(actorId, actorTierLabel(actorId), "review.delegated",
                "review_delegation", delegationId, workspaceId,
                Map.of("workspaceId", workspaceId.toString(),
                        "fromUserId", actorId.toString(),
                        "toUserId", delegateId.toString(),
                        "tier", tier,
                        "reason", reason == null ? "" : reason));

        log.info("Review delegation created: workspace={} tier={} from={} to={}",
                workspaceId, tier, actorId, delegateId);
        return toResponse(delegation);
    }

    /**
     * End a delegation.
     *
     * <p>Both the delegating Manager and any other Manager of the
     * workspace may end it — a delegation that cannot be revoked by
     * somebody else is not a delegation but an irrevocable gift.
     */
    @Transactional
    public ReviewDelegationResponse revoke(UUID actorId, UUID workspaceId, UUID delegationId) {
        requireDelegator(actorId, workspaceId);
        ReviewDelegation delegation = delegationRepository
                .findByIdAndWorkspaceId(delegationId, workspaceId)
                .orElseThrow(() -> new NotFoundException("Delegation not found"));
        if (!delegation.isActive()) {
            // Idempotent: revoking an already revoked delegation is not
            // an error, the end state is the same.
            return toResponse(delegation);
        }
        delegation.revoke(actorId);
        delegationRepository.save(delegation);

        String resourceId = delegation.getId() != null
                ? delegation.getId().toString()
                : "";
        auditLogService.record(actorId, actorTierLabel(actorId), "review.delegation_revoked",
                "review_delegation", resourceId, workspaceId,
                Map.of("workspaceId", workspaceId.toString(),
                        "toUserId", delegation.getToUserId().toString(),
                        "tier", delegation.getTier()));

        log.info("Review delegation revoked: workspace={} tier={} to={}",
                workspaceId, delegation.getTier(), delegation.getToUserId());
        return toResponse(delegation);
    }

    // -----------------------------------------------------------------
    // queries
    // -----------------------------------------------------------------

    /**
     * Every delegation of the workspace, in force or revoked.
     *
     * <p>Reading the delegation list is a Manager-tier act: it reveals
     * who is handling the oversight of the workspace.
     */
    @Transactional(readOnly = true)
    public List<ReviewDelegationResponse> list(UUID actorId, UUID workspaceId) {
        requireDelegator(actorId, workspaceId);
        List<ReviewDelegation> delegations =
                delegationRepository.findAllByWorkspaceIdOrderByCreatedAtDesc(workspaceId);
        return project(delegations);
    }

    /**
     * The delegates of one tier in the workspace.
     *
     * <p>This is the hook the notification path uses. The returned
     * people are added to the recipients of the tier, and the delegating
     * Manager is removed from them — handing work over means somebody
     * else now does it.
     *
     * <p>The result is empty when nobody delegated, which keeps the
     * notification path unchanged in the common case.
     */
    @Transactional(readOnly = true)
    public List<UUID> delegatesFor(UUID workspaceId, String tier) {
        if (workspaceId == null || tier == null) {
            return List.of();
        }
        try {
            return delegationRepository
                    .findAllByWorkspaceIdAndTierAndRevokedAtIsNullOrderByCreatedAtDesc(
                            workspaceId, tier)
                    .stream()
                    .map(ReviewDelegation::getToUserId)
                    .toList();
        } catch (Exception e) {
            // A delegation lookup must never abort a notification.
            log.warn("Failed to resolve the {} delegates of workspace {}: {}",
                    tier, workspaceId, e.getMessage());
            return List.of();
        }
    }

    /**
     * The Managers who delegated a tier of this workspace, and therefore
     * must <em>not</em> be notified for it.
     */
    @Transactional(readOnly = true)
    public List<UUID> delegatorsFor(UUID workspaceId, String tier) {
        if (workspaceId == null || tier == null) {
            return List.of();
        }
        try {
            return delegationRepository
                    .findAllByWorkspaceIdAndTierAndRevokedAtIsNullOrderByCreatedAtDesc(
                            workspaceId, tier)
                    .stream()
                    .map(ReviewDelegation::getFromUserId)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to resolve the {} delegators of workspace {}: {}",
                    tier, workspaceId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Whether the given user is an active delegate of the tier in the
     * workspace.
     */
    @Transactional(readOnly = true)
    public boolean isDelegate(UUID workspaceId, String tier, UUID userId) {
        if (workspaceId == null || tier == null || userId == null) {
            return false;
        }
        return delegatesFor(workspaceId, tier).contains(userId);
    }

    // -----------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------

    /**
     * The actor must be allowed to redistribute the oversight work of
     * the workspace.
     *
     * <p>Three conditions, all of them required:
     * <ul>
     *   <li>the {@code REVIEW_DELEGATION_MANAGE} permission — resolved
     *       from the matrix, so an administrator can revoke it;</li>
     *   <li>the {@code MANAGER} or {@code ADMIN} platform role — the
     *       act belongs to the Manager tier;</li>
     *   <li>workspace membership — the platform role alone does not
     *       prove a stake in this workspace's content.</li>
     * </ul>
     */
    private void requireDelegator(UUID actorId, UUID workspaceId) {
        authorizationService.requireWorkspaceMember(actorId, workspaceId);
        boolean admin = authorizationService.isAdmin(actorId);
        if (!admin) {
            permissionService.requireAnyPermission(actorId,
                    PermissionCodes.REVIEW_DELEGATION_MANAGE);
            Set<String> roles = authorizationService.platformRoles(actorId);
            if (!roles.contains(AuthorizationService.ROLE_MANAGER)) {
                throw new ForbiddenException(
                        "Only a Manager may delegate review work");
            }
        }
    }

    /**
     * The delegate must hold the permission of the tier they receive.
     */
    private void requireTierPermission(UUID userId, String tier) {
        String code = TIER_MANAGER.equals(tier)
                ? PermissionCodes.DOCUMENT_VERSION_APPROVE
                : PermissionCodes.DOCUMENT_VERSION_REVIEW;
        boolean allowed = authorizationService.isAdmin(userId)
                || permissionService.hasAnyPermission(userId, code);
        if (!allowed) {
            throw new BadRequestException(
                    "The delegate does not hold the permission required for the "
                            + tier + " tier");
        }
    }

    /**
     * Normalise and validate the tier label.
     */
    private String normalizeTier(String tier) {
        if (tier == null || tier.isBlank()) {
            throw new BadRequestException("A tier is required");
        }
        String normalized = tier.trim().toUpperCase(Locale.ROOT);
        if (!TIER_STAFF.equals(normalized) && !TIER_MANAGER.equals(normalized)) {
            throw new BadRequestException(
                    "Unknown tier '" + tier + "' — expected STAFF or MANAGER");
        }
        return normalized;
    }

    /**
     * The tier label used in audit records.
     */
    private String actorTierLabel(UUID actorId) {
        Set<String> roles = authorizationService.platformRoles(actorId);
        for (String code : roles) {
            if (AuthorizationService.ROLE_TECHNICAL_LEAD.equals(code)
                    || AuthorizationService.ROLE_ADMIN.equals(code)) {
                return code;
            }
        }
        return AuthorizationService.ROLE_MANAGER;
    }

    /**
     * Project the delegations, resolving the names of both parties in
     * one query instead of two per row.
     */
    private List<ReviewDelegationResponse> project(List<ReviewDelegation> delegations) {
        if (delegations.isEmpty()) {
            return List.of();
        }
        List<UUID> userIds = new ArrayList<>();
        for (ReviewDelegation d : delegations) {
            userIds.add(d.getFromUserId());
            userIds.add(d.getToUserId());
        }
        Map<UUID, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(new LinkedHashSet<>(userIds))) {
            names.put(user.getId(),
                    user.getDisplayName() != null && !user.getDisplayName().isBlank()
                            ? user.getDisplayName()
                            : user.getEmail());
        }
        return delegations.stream()
                .map(d -> ReviewDelegationResponse.of(d,
                        names.get(d.getFromUserId()),
                        names.get(d.getToUserId())))
                .toList();
    }

    private ReviewDelegationResponse toResponse(ReviewDelegation delegation) {
        return project(List.of(delegation)).get(0);
    }
}
