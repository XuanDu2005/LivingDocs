package com.livingdocs.modules.integrations.repository;

import com.livingdocs.modules.integrations.model.IntegrationConnection;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnection, UUID> {

    List<IntegrationConnection> findAllByOrderByConnectedAtDesc();

    List<IntegrationConnection> findAllByProviderOrderByConnectedAtDesc(IntegrationProvider provider);

    List<IntegrationConnection> findAllByWorkspaceIdOrderByConnectedAtDesc(UUID workspaceId);

    List<IntegrationConnection> findAllByWorkspaceIdAndProviderOrderByConnectedAtDesc(
            UUID workspaceId, IntegrationProvider provider);

    Optional<IntegrationConnection> findByProviderAndWorkspaceIdAndExternalAccount(
            IntegrationProvider provider, UUID workspaceId, String externalAccount);

    long countByProviderAndStatus(IntegrationProvider provider, com.livingdocs.modules.integrations.model.IntegrationStatus status);
}