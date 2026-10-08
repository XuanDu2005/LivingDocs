package com.livingdocs.modules.notification.repository;

import com.livingdocs.modules.notification.model.NotificationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPolicyRepository extends JpaRepository<NotificationPolicy, UUID> {

    Optional<NotificationPolicy> findByWorkspaceIdAndEventKind(UUID workspaceId, String eventKind);

    List<NotificationPolicy> findAllByWorkspaceIdOrderByEventKindAsc(UUID workspaceId);

    List<NotificationPolicy> findAllByWorkspaceIdIsNullOrderByEventKindAsc();
}