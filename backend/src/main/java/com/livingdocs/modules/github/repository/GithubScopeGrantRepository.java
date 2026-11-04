package com.livingdocs.modules.github.repository;

import com.livingdocs.modules.github.model.GithubScopeGrant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GithubScopeGrantRepository extends JpaRepository<GithubScopeGrant, UUID> {

    List<GithubScopeGrant> findByUserIdOrderByScopeAsc(UUID userId);

    Optional<GithubScopeGrant> findByUserIdAndScope(UUID userId, String scope);
}
