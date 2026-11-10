package com.livingdocs.modules.version.repository;

import com.livingdocs.modules.version.model.ActorRole;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.model.VersionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    List<DocumentVersion> findAllByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    Optional<DocumentVersion> findByDocumentIdAndVersionNumber(UUID documentId, Integer versionNumber);

    /**
     * Load a version with a pessimistic write lock.
     *
     * <p>Two Staff reviewers hitting "approve" on the same version at
     * the same time would otherwise both read the same state and both
     * write a verdict — the database would accept both rows and the
     * workflow state machine would then have two conflicting opinions
     * about the same version. The partial unique index added in V40 is
     * the last line of defence, but taking the lock here means the
     * second caller waits and then sees the first caller's result.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from DocumentVersion v where v.id = :id")
    Optional<DocumentVersion> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Every version of the given documents that currently sits in one of
     * the given statuses, newest submission first.
     *
     * <p>Replaces the previous "load every document, then load every
     * version of every document and filter in memory" approach, which
     * ran one query per document.
     */
    List<DocumentVersion> findAllByDocumentIdInAndStatusInOrderBySubmittedAtDesc(
            Collection<UUID> documentIds, Collection<VersionStatus> statuses);

    long countByDocumentId(UUID documentId);

    List<DocumentVersion> findAllByDocumentIdAndStatusOrderByCreatedAtDesc(UUID documentId, VersionStatus status);

    /**
     * Every version of a document that is in one of the given statuses,
     * newest version first.
     *
     * <p>Used by the bulk rollback to find the version that was published
     * before the current head: the head of a document is
     * {@code PUBLISHED} and the versions it replaced are
     * {@code SUPERSEDED}, so both statuses have to be looked at to
     * reconstruct the publication history.
     */
    List<DocumentVersion> findAllByDocumentIdAndStatusInOrderByVersionNumberDesc(
            UUID documentId, Collection<VersionStatus> statuses);

    /** All version numbers for a document, ordered newest first. Used for the timeline UI. */
    List<Integer> findDistinctVersionNumberByDocumentIdOrderByVersionNumberDesc(UUID documentId);

    /**
     * Every version of the given documents, newest first.
     *
     * <p>Used by the health &amp; compliance export, which needs the
     * latest version of every document of a workspace. Loading them in
     * one query avoids a query per document — a workspace with a
     * thousand documents would otherwise cost a thousand round trips.
     */
    List<DocumentVersion> findAllByDocumentIdInOrderByVersionNumberDesc(
            Collection<UUID> documentIds);

    /**
     * Every version of the given documents that is in one of the given
     * statuses, newest version first.
     *
     * <p>The compliance export reports the versions that are actually
     * live — the ones a reader of the documentation sees.
     */
    List<DocumentVersion> findAllByDocumentIdInAndStatusInOrderByVersionNumberDesc(
            Collection<UUID> documentIds, Collection<VersionStatus> statuses);

    /**
     * Every version in the given documents that the user submitted for
     * review, newest submission first.
     *
     * <p>Used by the "my submissions" bucket of the review queue: an
     * author needs to see what happened to the versions they handed over,
     * and the only reliable link between the two is the
     * {@code submitted_by_user_id} column.
     */
    List<DocumentVersion> findAllBySubmittedByUserIdAndDocumentIdInOrderBySubmittedAtDesc(
            UUID submittedByUserId, Collection<UUID> documentIds);

    /**
     * The workspace-level change log: every version of every document
     * of a workspace, filtered by the axes the requirement names.
     *
     * <p>The filter is applied entirely in SQL — a document, a
     * repository, the role of the actor and a date range. Filtering in
     * memory after loading the table would be unusable on a workspace
     * with a long history.
     *
     * <p>The workspace itself is part of the predicate and not only of
     * the caller: the endpoint is scoped by path, and a version that
     * belongs to a document of another workspace must never show up in
     * the log of this one.
     *
     * <p>Each optional axis is expressed as a boolean flag plus a
     * non-null value rather than as a nullable parameter. A parameter
     * that is only used in an {@code is null} test leaves PostgreSQL
     * without any type to infer from, and the query fails with
     * "could not determine data type of parameter" — the flag form
     * gives every parameter a comparison with a typed column, so the
     * database can resolve its type on its own.
     *
     * <p>{@code actorRole} is compared as an enum — the column is
     * mapped with {@code EnumType.STRING}, so the parameter is bound
     * as its name.
     *
     * @param documentId      restrict to one document
     * @param repositoryId    restrict to the documents of one repository
     * @param actorRole       restrict to the versions produced by one role
     * @param from            inclusive lower bound on the creation time
     * @param to              inclusive upper bound on the creation time
     */
    @Query("""
            select v from DocumentVersion v
              join Document d on d.id = v.documentId
             where d.workspaceId = :workspaceId
               and (:byDocument = false or v.documentId = :documentId)
               and (:byRepository = false or d.repositoryId = :repositoryId)
               and (:byActorRole = false or v.actorRole = :actorRole)
               and (:byFrom = false or v.createdAt >= :from)
               and (:byTo = false or v.createdAt <= :to)
            """)
    Page<DocumentVersion> findChangeLog(@Param("workspaceId") UUID workspaceId,
                                         @Param("byDocument") boolean byDocument,
                                         @Param("documentId") UUID documentId,
                                         @Param("byRepository") boolean byRepository,
                                         @Param("repositoryId") UUID repositoryId,
                                         @Param("byActorRole") boolean byActorRole,
                                         @Param("actorRole") ActorRole actorRole,
                                         @Param("byFrom") boolean byFrom,
                                         @Param("from") OffsetDateTime from,
                                         @Param("byTo") boolean byTo,
                                         @Param("to") OffsetDateTime to,
                                         Pageable pageable);
}
