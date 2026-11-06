package com.livingdocs.modules.workspace.repository;

import com.livingdocs.modules.workspace.model.WorkspaceCiToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * The CI tokens of the workspaces.
 *
 * <p>One row per workspace. The lookup by hash is what authenticates a
 * CI request: the presented token is hashed and the matching row names
 * the workspace the request is allowed to act on.
 */
@Repository
public interface WorkspaceCiTokenRepository extends JpaRepository<WorkspaceCiToken, UUID> {

    Optional<WorkspaceCiToken> findByWorkspaceId(UUID workspaceId);

    Optional<WorkspaceCiToken> findByTokenHash(String tokenHash);
}
