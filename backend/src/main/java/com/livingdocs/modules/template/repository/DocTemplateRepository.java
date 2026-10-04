package com.livingdocs.modules.template.repository;

import com.livingdocs.modules.template.model.DocTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocTemplateRepository extends JpaRepository<DocTemplate, UUID> {

    /** All global (workspace-agnostic) templates in the library. */
    List<DocTemplate> findAllByWorkspaceIdIsNullOrderByDocTypeAscNameAsc();

    /** All templates that belong to a single workspace. */
    List<DocTemplate> findAllByWorkspaceIdOrderByDocTypeAscNameAsc(UUID workspaceId);

    /** The default template for a given documentation type within a workspace. */
    Optional<DocTemplate> findFirstByWorkspaceIdAndDocTypeAndIsDefaultTrue(UUID workspaceId, String docType);

    /** The global default template for a given documentation type. */
    Optional<DocTemplate> findFirstByWorkspaceIdIsNullAndDocTypeAndIsDefaultTrue(String docType);

    /** Look up a specific (slug, version) tuple. */
    Optional<DocTemplate> findBySlugAndVersion(String slug, Integer version);

    /** All versions of a given slug, ordered newest first. */
    List<DocTemplate> findAllBySlugOrderByVersionDesc(String slug);
}