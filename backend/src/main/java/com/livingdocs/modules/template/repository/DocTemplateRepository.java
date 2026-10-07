package com.livingdocs.modules.template.repository;

import com.livingdocs.modules.template.model.DocTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Update the JSONB {@code body} column with an explicit cast so
     * Postgres accepts the {@code String} value. Hibernate's default
     * binding sends {@code varchar}, which the server refuses to coerce.
     *
     * <p>Uses {@code clearAutomatically} so the persistence context does
     * not re-flush the dirty entity on commit (which would trigger the
     * same coercion error).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE doc_templates SET body = CAST(:body AS jsonb), " +
            "updated_at = NOW() WHERE id = :id", nativeQuery = true)
    void updateBody(@Param("id") UUID id, @Param("body") String body);
}