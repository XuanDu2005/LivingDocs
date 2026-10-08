package com.livingdocs.modules.integrations.repository;

import com.livingdocs.modules.integrations.model.IntegrationEvent;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationEventRepository extends JpaRepository<IntegrationEvent, UUID> {

    Optional<IntegrationEvent> findByProviderAndDeliveryId(IntegrationProvider provider, String deliveryId);

    List<IntegrationEvent> findAllByOrderByReceivedAtDesc(Pageable pageable);

    List<IntegrationEvent> findAllByConnectionIdOrderByReceivedAtDesc(UUID connectionId, Pageable pageable);

    List<IntegrationEvent> findAllByProviderOrderByReceivedAtDesc(IntegrationProvider provider, Pageable pageable);
}