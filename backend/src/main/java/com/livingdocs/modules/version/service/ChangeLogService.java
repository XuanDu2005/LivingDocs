package com.livingdocs.modules.version.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.user.model.User;
import com.livingdocs.modules.user.repository.UserRepository;
import com.livingdocs.modules.version.dto.ChangeLogEntryResponse;
import com.livingdocs.modules.version.dto.ChangeLogFilter;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The workspace-level change log.
 *
 * <p>The per-document timeline answers "what happened to this
 * document"; this service answers "what changed in this workspace
 * during that period, and who changed it". The requirement names both
 * views, and they are not interchangeable: a reviewer who wants to see
 * every AI change of a quarter cannot ask that question through a
 * timeline that is scoped to one document.
 *
 * <p>The filter axes are the ones the requirement lists — role, document,
 * repository and date range — and they are applied in SQL rather than
 * in memory, because a workspace with a long history would otherwise
 * have to load its whole version table to answer one question.
 */
@Service
public class ChangeLogService {

    /**
     * Upper bound on the number of rows returned in one response.
     *
     * <p>A change log is a browsing surface, not a data export: an
     * unbounded page would let a single request pull the entire
     * version history of a workspace into memory.
     */
    public static final int MAX_PAGE_SIZE = 200;

    private final DocumentVersionRepository versionRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final WorkspaceService workspaceService;

    public ChangeLogService(DocumentVersionRepository versionRepository,
                            DocumentRepository documentRepository,
                            UserRepository userRepository,
                            WorkspaceService workspaceService) {
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.workspaceService = workspaceService;
    }

    /**
     * Read a page of the change log of a workspace.
     *
     * @param actorId     the caller; has to be a member of the workspace
     * @param workspaceId the workspace the log belongs to
     * @param filter      the axes to restrict the log on; every field is optional
     */
    @Transactional(readOnly = true)
    public Page<ChangeLogEntryResponse> list(UUID actorId,
                                             UUID workspaceId,
                                             ChangeLogFilter filter,
                                             int page,
                                             int size) {
        // Membership is the baseline: reading the trail means reading
        // who did what in this workspace's content.
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MEMBER);

        // The document of the filter has to belong to the workspace in
        // the path — otherwise a member of two workspaces could read
        // the history of a document of the other one through the wrong
        // URL.
        if (filter.documentId() != null) {
            requireDocumentInWorkspace(workspaceId, filter.documentId());
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(filter.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                        filter.sortProperty()));

        // The repository axis is resolved through the documents: a
        // version belongs to a repository through its document, so a
        // repository that has no document in this workspace cannot have
        // a version in it either. Answering that without a query keeps
        // a foreign repository id from reaching the log at all.
        if (filter.repositoryId() != null
                && documentRepository
                        .findAllByWorkspaceIdAndRepositoryId(workspaceId, filter.repositoryId())
                        .isEmpty()) {
            return Page.empty(pageable);
        }

        Page<DocumentVersion> versions = versionRepository.findChangeLog(
                workspaceId,
                filter.documentId() != null, filter.documentId(),
                filter.repositoryId() != null, filter.repositoryId(),
                filter.actorRoleEnum() != null, filter.actorRoleEnum(),
                filter.from() != null, filter.from(),
                filter.to() != null, filter.to(),
                pageable);

        // The document metadata and the actor names of the whole page
        // are resolved in one batch each — resolving them row by row
        // would be a query per row.
        Map<UUID, Document> documents = documentsOf(versions.getContent());
        Map<UUID, String> actorNames = actorNamesOf(versions.getContent());

        return versions.map(version -> toEntry(version, documents, actorNames));
    }

    /**
     * Project a version into its change-log row.
     *
     * <p>The document metadata and the actor's name come from the
     * batch-resolved maps of the page.
     */
    private ChangeLogEntryResponse toEntry(DocumentVersion version,
                                           Map<UUID, Document> documents,
                                           Map<UUID, String> actorNames) {
        Document document = documents.get(version.getDocumentId());
        // The actor may be null — an AI version is not necessarily
        // attributed to a user — and a lookup of a null key must not
        // fail the whole page.
        UUID actorUserId = version.getActorUserId();
        return ChangeLogEntryResponse.from(
                version,
                document == null ? null : document.getTitle(),
                document == null ? null : document.getSlug(),
                document == null ? null : document.getRepositoryId(),
                actorUserId == null ? null : actorNames.get(actorUserId));
    }

    /**
     * The documents of the current page, keyed by id.
     *
     * <p>Loaded in one query: a page of 50 versions would otherwise be
     * 50 title lookups.
     */
    private Map<UUID, Document> documentsOf(List<DocumentVersion> versions) {
        List<UUID> documentIds = versions.stream()
                .map(DocumentVersion::getDocumentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (documentIds.isEmpty()) {
            return new HashMap<>();
        }
        Map<UUID, Document> byId = new HashMap<>();
        for (Document document : documentRepository.findAllById(documentIds)) {
            byId.put(document.getId(), document);
        }
        return byId;
    }

    /**
     * The display names of the actors of the current page, keyed by
     * user id.
     *
     * <p>A change log that only carries a UUID does not answer "who
     * changed this?" — the name is resolved in one batch for the same
     * reason the audit trail resolves it.
     */
    private Map<UUID, String> actorNamesOf(List<DocumentVersion> versions) {
        List<UUID> actorIds = versions.stream()
                .map(DocumentVersion::getActorUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (actorIds.isEmpty()) {
            return new HashMap<>();
        }
        Map<UUID, String> byId = new HashMap<>();
        for (User user : userRepository.findAllById(actorIds)) {
            String name = user.getDisplayName();
            if (name == null || name.isBlank()) {
                name = user.getEmail();
            }
            if (name == null || name.isBlank()) {
                name = user.getId().toString();
            }
            byId.put(user.getId(), name);
        }
        return byId;
    }

    /**
     * The document must exist and belong to the workspace of the path.
     */
    private Document requireDocumentInWorkspace(UUID workspaceId, UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("Document not found"));
        if (!document.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Document not found in this workspace");
        }
        return document;
    }
}
