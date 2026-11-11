package com.livingdocs.modules.workspace.repository;

import com.livingdocs.modules.workspace.model.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    Optional<Workspace> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * How many workspaces this user owns.
     *
     * <p>Used by the administrator's "delete user" pre-check: the
     * {@code workspaces.owner_id} foreign key is {@code ON DELETE
     * RESTRICT}, so an account that still owns a workspace cannot be
     * deleted until the ownership is transferred.
     */
    long countByOwnerId(UUID ownerId);
}