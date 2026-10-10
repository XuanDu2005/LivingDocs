package com.livingdocs.modules.notification.repository;

import com.livingdocs.modules.notification.model.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, UUID> {

    Optional<NotificationChannel> findByWorkspaceIdAndEventKind(UUID workspaceId, String eventKind);

    List<NotificationChannel> findAllByWorkspaceIdOrderByEventKindAsc(UUID workspaceId);
}
