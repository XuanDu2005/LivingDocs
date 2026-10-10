package com.livingdocs.modules.admin.repository;

import com.livingdocs.modules.admin.model.PlatformDocTypePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlatformDocTypePolicyRepository
        extends JpaRepository<PlatformDocTypePolicy, UUID> {
    List<PlatformDocTypePolicy> findAllByOrderByDocTypeAsc();
    Optional<PlatformDocTypePolicy> findByDocType(String docType);
}
