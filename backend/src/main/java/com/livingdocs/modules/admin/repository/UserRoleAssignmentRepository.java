package com.livingdocs.modules.admin.repository;

import com.livingdocs.modules.admin.model.UserRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignment, UUID> {

    /** Only currently-active (non-revoked) assignments are returned. */
    @Query("""
           SELECT a FROM UserRoleAssignment a
           WHERE a.userId = :userId AND a.revokedAt IS NULL
           """)
    List<UserRoleAssignment> findActiveByUserId(UUID userId);

    /** All assignments (active + revoked) for audit purposes. */
    List<UserRoleAssignment> findAllByUserId(UUID userId);

    Optional<UserRoleAssignment> findByUserIdAndRoleId(UUID userId, UUID roleId);

    @Query("""
           SELECT a FROM UserRoleAssignment a
           WHERE a.userId = :userId AND a.roleId = :roleId AND a.revokedAt IS NULL
           """)
    Optional<UserRoleAssignment> findActiveByUserIdAndRoleId(UUID userId, UUID roleId);

    /** Returns role codes the user currently has. Empty list if none. */
    @Query("""
           SELECT r.code FROM UserRoleAssignment a, Role r
           WHERE a.roleId = r.id
             AND a.userId = :userId
             AND a.revokedAt IS NULL
           """)
    List<String> findActiveRoleCodesByUserId(UUID userId);
}