package com.livingdocs.modules.user.repository;

import com.livingdocs.modules.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    /** All users that have not been disabled by an administrator. */
    List<User> findAllByEnabledTrue();

    /**
     * IDs of every user that currently holds the given platform role
     * (regardless of their {@code enabled} status). The caller can
     * combine this with {@link #findAllByEnabledTrue()} if the
     * broadcast should skip disabled accounts.
     */
    @Query("""
           SELECT a.userId FROM UserRoleAssignment a, Role r
             WHERE a.roleId = r.id
               AND r.code = :roleCode
               AND a.revokedAt IS NULL
           """)
    List<UUID> findUserIdsByActiveRoleCode(String roleCode);
}