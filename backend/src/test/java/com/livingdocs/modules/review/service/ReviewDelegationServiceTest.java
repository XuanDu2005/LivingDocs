package com.livingdocs.modules.review.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.ConflictException;
import com.livingdocs.common.exception.ForbiddenException;
import com.livingdocs.common.security.AuthorizationService;
import com.livingdocs.common.security.PermissionService;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.review.dto.CreateDelegationRequest;
import com.livingdocs.modules.review.model.ReviewDelegation;
import com.livingdocs.modules.review.repository.ReviewDelegationRepository;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.workspace.repository.WorkspaceMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules of the review delegation.
 *
 * <p>Delegating is a Manager act, the delegate has to be able to do the
 * work, and a delegation that only redirects a notification would be
 * pointless.
 */
class ReviewDelegationServiceTest {

    private ReviewDelegationRepository delegationRepository;
    private WorkspaceMemberRepository memberRepository;
    private PermissionService permissionService;
    private AuthorizationService authorizationService;
    private ReviewDelegationService service;

    private UUID actorId;
    private UUID delegateId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        delegationRepository = mock(ReviewDelegationRepository.class);
        memberRepository = mock(WorkspaceMemberRepository.class);
        permissionService = mock(PermissionService.class);
        authorizationService = mock(AuthorizationService.class);

        service = new ReviewDelegationService(
                delegationRepository,
                memberRepository,
                mock(UserRepository.class),
                permissionService,
                authorizationService,
                mock(AuditLogService.class));

        actorId = UUID.randomUUID();
        delegateId = UUID.randomUUID();
        workspaceId = UUID.randomUUID();

        when(authorizationService.platformRoles(actorId))
                .thenReturn(Set.of(AuthorizationService.ROLE_MANAGER));
        when(authorizationService.isAdmin(actorId)).thenReturn(false);
        when(authorizationService.isAdmin(delegateId)).thenReturn(false);
        when(permissionService.hasAnyPermission(any(), anyString())).thenReturn(true);
        when(delegationRepository.save(any(ReviewDelegation.class)))
                .thenAnswer(inv -> {
                    ReviewDelegation d = inv.getArgument(0);
                    // The persistence layer assigns the id; the mock
                    // has to stand in for it.
                    var field = ReviewDelegation.class.getDeclaredField("id");
                    field.setAccessible(true);
                    field.set(d, UUID.randomUUID());
                    return d;
                });
        when(delegationRepository
                .findFirstByWorkspaceIdAndToUserIdAndTierAndRevokedAtIsNull(
                        any(), any(), anyString()))
                .thenReturn(Optional.empty());
    }

    /**
     * The happy path: a Manager delegates the approval tier of their
     * workspace to another member.
     */
    @Test
    void aManagerMayDelegateTheirWorkspace() {
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, delegateId))
                .thenReturn(true);

        var response = service.create(actorId, workspaceId,
                new CreateDelegationRequest(delegateId, "MANAGER", "on leave"));

        assertEquals(delegateId, response.toUserId());
        assertEquals("MANAGER", response.tier());
        assertTrue(response.active());
        verify(delegationRepository).save(any(ReviewDelegation.class));
    }

    /**
     * A delegate who cannot approve would receive notifications they
     * can never act on — the work would end up in a black hole.
     */
    @Test
    void theDelegateMustHoldThePermissionOfTheTier() {
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, delegateId))
                .thenReturn(true);
        when(permissionService.hasAnyPermission(any(), anyString())).thenReturn(false);

        assertThrows(BadRequestException.class, () -> service.create(actorId, workspaceId,
                new CreateDelegationRequest(delegateId, "MANAGER", null)));

        verify(delegationRepository, never()).save(any());
    }

    /**
     * A delegate outside the workspace could not act on the work either:
     * the review guards require workspace membership.
     */
    @Test
    void theDelegateMustBeAMemberOfTheWorkspace() {
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, delegateId))
                .thenReturn(false);

        assertThrows(BadRequestException.class, () -> service.create(actorId, workspaceId,
                new CreateDelegationRequest(delegateId, "STAFF", null)));

        verify(delegationRepository, never()).save(any());
    }

    /**
     * Delegating to yourself is meaningless.
     */
    @Test
    void youCannotDelegateToYourself() {
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, actorId))
                .thenReturn(true);

        assertThrows(BadRequestException.class, () -> service.create(actorId, workspaceId,
                new CreateDelegationRequest(actorId, "MANAGER", null)));
    }

    /**
     * A duplicate delegation is refused with a clear message rather
     * than letting the partial unique index raise a constraint
     * violation.
     */
    @Test
    void aDuplicateDelegationIsRefused() {
        when(memberRepository.existsByWorkspaceIdAndUserId(workspaceId, delegateId))
                .thenReturn(true);
        when(delegationRepository
                .findFirstByWorkspaceIdAndToUserIdAndTierAndRevokedAtIsNull(
                        workspaceId, delegateId, "MANAGER"))
                .thenReturn(Optional.of(new ReviewDelegation(
                        workspaceId, actorId, delegateId, "MANAGER", null)));

        assertThrows(ConflictException.class, () -> service.create(actorId, workspaceId,
                new CreateDelegationRequest(delegateId, "MANAGER", null)));
    }

    /**
     * An unknown tier is a bad request, not a silent default.
     */
    @Test
    void anUnknownTierIsRefused() {
        assertThrows(BadRequestException.class, () -> service.create(actorId, workspaceId,
                new CreateDelegationRequest(delegateId, "DIRECTOR", null)));
    }

    /**
     * Delegating is a Manager-tier act. A Staff member with the
     * permission somehow granted must still be refused: the act belongs
     * to the Manager tier.
     */
    @Test
    void aNonManagerMayNotDelegate() {
        when(authorizationService.platformRoles(actorId))
                .thenReturn(Set.of(AuthorizationService.ROLE_STAFF));

        assertThrows(ForbiddenException.class, () -> service.create(actorId, workspaceId,
                new CreateDelegationRequest(delegateId, "MANAGER", null)));

        verify(delegationRepository, never()).save(any());
    }

    /**
     * The delegators and the delegates of a tier are what the
     * notification path needs: the delegate is added, the delegator is
     * removed.
     */
    @Test
    void theDelegationLookupReturnsBothParties() {
        ReviewDelegation delegation = new ReviewDelegation(
                workspaceId, actorId, delegateId, "MANAGER", null);
        when(delegationRepository
                .findAllByWorkspaceIdAndTierAndRevokedAtIsNullOrderByCreatedAtDesc(
                        workspaceId, "MANAGER"))
                .thenReturn(List.of(delegation));

        assertEquals(List.of(delegateId), service.delegatesFor(workspaceId, "MANAGER"));
        assertEquals(List.of(actorId), service.delegatorsFor(workspaceId, "MANAGER"));
    }

    /**
     * A revoked delegation routes nothing: the delegator is back in
     * charge and the delegate is not notified.
     */
    @Test
    void aRevokedDelegationRoutesNothing() {
        ReviewDelegation delegation = new ReviewDelegation(
                workspaceId, actorId, delegateId, "MANAGER", null);
        delegation.revoke(actorId);
        when(delegationRepository
                .findAllByWorkspaceIdAndTierAndRevokedAtIsNullOrderByCreatedAtDesc(
                        workspaceId, "MANAGER"))
                .thenReturn(List.of());

        assertTrue(service.delegatesFor(workspaceId, "MANAGER").isEmpty());
        assertTrue(service.delegatorsFor(workspaceId, "MANAGER").isEmpty());
    }
}
