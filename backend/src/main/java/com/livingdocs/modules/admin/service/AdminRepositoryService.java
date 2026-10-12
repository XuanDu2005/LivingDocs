package com.livingdocs.modules.admin.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.common.security.CurrentUser;
import com.livingdocs.modules.admin.dto.AdminRepositoryResponse;
import com.livingdocs.modules.ai.indexing.IndexJob;
import com.livingdocs.modules.ai.indexing.IndexJobRepository;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.workspace.model.Workspace;
import com.livingdocs.modules.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cross-tenant repository administration.
 *
 * <p>Unlike {@code GithubRepositoryService} which is workspace-scoped,
 * this service bypasses the per-workspace membership check so a
 * platform administrator can browse every repository on the platform
 * from a single table. Force-reindex and unlink operations are also
 * exposed here, gated by {@code ADMIN} role at the controller layer.
 */
@Service
public class AdminRepositoryService {

    private final RepositoryRepository repositoryRepository;
    private final WorkspaceRepository workspaceRepository;
    private final DocumentRepository documentRepository;
    private final DriftAlertRepository driftAlertRepository;
    private final IndexJobRepository indexJobRepository;
    private final com.livingdocs.modules.github.service.GithubRepositoryService githubRepositoryService;
    private final com.livingdocs.modules.ai.indexing.IndexingService indexingService;
    private final AuditLogService auditLogService;

    public AdminRepositoryService(RepositoryRepository repositoryRepository,
                                  WorkspaceRepository workspaceRepository,
                                  DocumentRepository documentRepository,
                                  DriftAlertRepository driftAlertRepository,
                                  IndexJobRepository indexJobRepository,
                                  com.livingdocs.modules.github.service.GithubRepositoryService githubRepositoryService,
                                  com.livingdocs.modules.ai.indexing.IndexingService indexingService,
                                  AuditLogService auditLogService) {
        this.repositoryRepository = repositoryRepository;
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
        this.driftAlertRepository = driftAlertRepository;
        this.indexJobRepository = indexJobRepository;
        this.githubRepositoryService = githubRepositoryService;
        this.indexingService = indexingService;
        this.auditLogService = auditLogService;
    }

    /**
     * Return every connected repository on the platform, enriched with
     * workspace context and operational aggregates (documents, drift,
     * last indexing job). The result is sorted by most-recent sync
     * (nulls last) so the most active repos float to the top.
     */
    @Transactional(readOnly = true)
    public List<AdminRepositoryResponse> listAll() {
        // Cache workspace names — we expect far fewer workspaces than
        // repositories, so a single pass per call is fine.
        Map<UUID, String> workspaceNames = new HashMap<>();
        Map<UUID, String> workspaceSlugs = new HashMap<>();
        for (Workspace w : workspaceRepository.findAll()) {
            workspaceNames.put(w.getId(), w.getName());
            workspaceSlugs.put(w.getId(), w.getSlug());
        }

        List<Repository> repos = repositoryRepository
                .findAllByOrderByLastSyncedAtDescConnectedAtDesc();

        return repos.stream()
                .map(r -> toResponse(r,
                        workspaceNames.getOrDefault(r.getWorkspaceId(), "—"),
                        workspaceSlugs.getOrDefault(r.getWorkspaceId(), "")))
                .toList();
    }

    /**
     * Force-reindex the workspace that owns a repository. The admin
     * does not need to be a workspace member — the controller checks
     * the platform role. The job is enqueued asynchronously and the
     * call returns immediately.
     */
    @Transactional
    public IndexJob forceReindex(UUID repositoryId) {
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        UUID actorId = CurrentUser.requireId();
        UUID workspaceId = repo.getWorkspaceId();

        IndexJob job = indexingService.enqueueReindexAll(workspaceId, actorId);

        auditLogService.record(actorId, "ADMIN", "repository.reindex",
                "repository", repo.getId().toString(), null,
                Map.of(
                        "workspaceId", workspaceId.toString(),
                        "fullName", repo.getFullName(),
                        "jobId", job.getId().toString()
                ));
        return job;
    }

    /**
     * Unlink (disconnect) a repository from its workspace. The
     * underlying {@link com.livingdocs.modules.github.service.GithubRepositoryService#disconnect}
     * call handles the cascade; here we just add the audit entry and
     * return the (now-removed) repository id.
     */
    @Transactional
    public UUID unlink(UUID repositoryId) {
        Repository repo = repositoryRepository.findById(repositoryId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        UUID actorId = CurrentUser.requireId();
        UUID workspaceId = repo.getWorkspaceId();
        String fullName = repo.getFullName();

        githubRepositoryService.disconnect(workspaceId, repositoryId);

        auditLogService.record(actorId, "ADMIN", "repository.unlink",
                "repository", repositoryId.toString(), null,
                Map.of(
                        "workspaceId", workspaceId.toString(),
                        "fullName", fullName
                ));
        return repositoryId;
    }

    // -------- helpers --------

    private AdminRepositoryResponse toResponse(Repository r, String workspaceName, String workspaceSlug) {
        long documents = documentRepository.countByWorkspaceId(r.getWorkspaceId());
        long openDrift = driftAlertRepository.countByRepositoryIdAndResolutionStatus(
                r.getId(), DriftResolution.OPEN);
        long totalDrift = driftAlertRepository.countByRepositoryId(r.getId());

        // Indexing jobs are workspace-scoped, so we surface the most
        // recent one for the workspace as a "last indexed" proxy.
        Optional<IndexJob> last = indexJobRepository
                .findTop50ByWorkspaceIdOrderByCreatedAtDesc(r.getWorkspaceId())
                .stream()
                .findFirst();

        return new AdminRepositoryResponse(
                r.getId().toString(),
                r.getGithubId(),
                r.getOwner(),
                r.getName(),
                r.getFullName(),
                r.getDefaultBranch(),
                r.getHtmlUrl(),
                r.getDescription(),
                r.isPrivate(),
                r.getStatus().name(),
                r.getWorkspaceId().toString(),
                workspaceName,
                workspaceSlug,
                r.getConnectedBy() == null ? null : r.getConnectedBy().toString(),
                r.getConnectedAt(),
                r.getLastSyncedAt(),
                documents,
                openDrift,
                totalDrift,
                last.map(j -> j.getStatus().name()).orElse(null),
                last.map(j -> j.getUpdatedAt() != null ? j.getUpdatedAt() : j.getCreatedAt()).orElse(null),
                r.getConnectedAt()
        );
    }
}
