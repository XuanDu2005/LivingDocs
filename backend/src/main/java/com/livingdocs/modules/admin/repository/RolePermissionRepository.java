package com.livingdocs.modules.admin.repository;

import com.livingdocs.modules.admin.model.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RolePermissionRepository extends JpaRepository<RolePermission, UUID> {

    List<RolePermission> findByRoleId(UUID roleId);

    List<RolePermission> findByPermissionId(UUID permissionId);

    boolean existsByRoleIdAndPermissionId(UUID roleId, UUID permissionId);

    long countByRoleId(UUID roleId);

    /**
     * The permission <em>codes</em> granted to a role. Resolving the
     * codes in the database keeps the hot authorization path to a single
     * query instead of a row fetch plus an id→code mapping in Java.
     */
    @Query("""
           SELECT p.code FROM RolePermission rp, Permission p
           WHERE rp.permissionId = p.id
             AND rp.roleId = :roleId
           """)
    List<String> findPermissionCodesByRoleId(@Param("roleId") UUID roleId);

    @Modifying
    @Query("DELETE FROM RolePermission rp WHERE rp.roleId = :roleId AND rp.permissionId = :permissionId")
    int deleteByRoleIdAndPermissionId(@Param("roleId") UUID roleId,
                                      @Param("permissionId") UUID permissionId);

    @Modifying
    @Query("DELETE FROM RolePermission rp WHERE rp.roleId = :roleId")
    int deleteByRoleId(@Param("roleId") UUID roleId);
}
