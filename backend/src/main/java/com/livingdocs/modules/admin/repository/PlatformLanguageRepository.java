package com.livingdocs.modules.admin.repository;

import com.livingdocs.modules.admin.model.PlatformLanguage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PlatformLanguageRepository extends JpaRepository<PlatformLanguage, UUID> {
    List<PlatformLanguage> findAllByOrderBySortOrderAscLanguageCodeAsc();
    Optional<PlatformLanguage> findByLanguageCode(String languageCode);
}
