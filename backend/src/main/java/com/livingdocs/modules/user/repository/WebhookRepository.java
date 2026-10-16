package com.livingdocs.modules.user.repository;

import com.livingdocs.modules.integrations.model.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WebhookRepository extends JpaRepository<Webhook, UUID> {
    List<Webhook> findByWorkspaceIdAndIsActiveTrue(UUID workspaceId);
}