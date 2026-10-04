package com.livingdocs.modules.github.repository;

import com.livingdocs.modules.github.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
    Optional<WebhookEvent> findByDeliveryId(String deliveryId);
}