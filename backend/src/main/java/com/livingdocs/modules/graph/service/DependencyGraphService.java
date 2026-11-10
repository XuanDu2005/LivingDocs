package com.livingdocs.modules.graph.service;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.model.RepositoryModule;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryModuleRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.graph.model.DependencyEdge;
import com.livingdocs.modules.graph.model.DependencyGraphResponse;
import com.livingdocs.modules.graph.model.DependencyNode;
import com.livingdocs.modules.graph.model.ImpactAnalysisResponse;
import com.livingdocs.modules.link.model.CodeDocumentLink;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.workspace.model.WorkspaceRole;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The documentation-to-code dependency graph and the impact analysis.
 *
 * <p>No new table is involved: the graph is derived from the data that
 * is already there —
 *
 * <ul>
 *   <li>{@code documents} — the documentation side,</li>
 *   <li>{@code code_document_links} — the documentation ↔ code edge,</li>
 *   <li>{@code code_entities} — the code side,</li>
 *   <li>{@code repository_modules} — the package a code entity lives in,</li>
 *   <li>{@code repositories} — the top of the containment chain.</li>
 * </ul>
 *
 * <p>The graph is therefore always in sync with the links: there is no
 * second copy of the truth to keep consistent.
 *
 * <p>The <em>impact analysis</em> is the more important of the two
 * surfaces. A graph is a picture; an impact analysis is an answer to
 * "I am about to change this method — what do I have to touch?".
 */
@Service
public class DependencyGraphService {

    /** The deepest traversal the endpoint allows. */
    public static final int MAX_DEPTH = 3;

    /**
     * Upper bound on the number of nodes one response may carry.
     *
     * <p>A workspace with a big monorepo can have tens of thousands of
     * code entities. The graph is a browsing surface, not a data
     * export, so it is capped: without a cap a single request could
     * pull the whole index of the workspace into memory.
     */
    public static final int MAX_NODES = 500;

    /** Upper bound on the number of documents one graph may contain. */
    public static final int MAX_DOCUMENTS = 200;

    private final DocumentRepository documentRepository;
    private final CodeEntityRepository codeEntityRepository;
    private final CodeDocumentLinkRepository linkRepository;
    private final RepositoryRepository repositoryRepository;
    private final RepositoryModuleRepository moduleRepository;
    private final DriftAlertRepository driftAlertRepository;
    private final PullRequestRepository pullRequestRepository;
    private final WorkspaceService workspaceService;

    public DependencyGraphService(DocumentRepository documentRepository,
                                  CodeEntityRepository codeEntityRepository,
                                  CodeDocumentLinkRepository linkRepository,
                                  RepositoryRepository repositoryRepository,
                                  RepositoryModuleRepository moduleRepository,
                                  DriftAlertRepository driftAlertRepository,
                                  PullRequestRepository pullRequestRepository,
                                  WorkspaceService workspaceService) {
        this.documentRepository = documentRepository;
        this.codeEntityRepository = codeEntityRepository;
        this.linkRepository = linkRepository;
        this.repositoryRepository = repositoryRepository;
        this.moduleRepository = moduleRepository;
        this.driftAlertRepository = driftAlertRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.workspaceService = workspaceService;
    }

    // =================================================================
    // The graph
    // =================================================================

    /**
     * Build the dependency graph of a workspace.
     *
     * @param actorId      the caller; has to be a member of the workspace
     * @param workspaceId  the workspace the graph belongs to
     * @param repositoryId restrict the graph to one repository, or {@code null}
     * @param documentId   restrict the graph to one document and its
     *                     neighbourhood, or {@code null}
     * @param depth        how many hops from the roots are followed
     */
    @Transactional(readOnly = true)
    public DependencyGraphResponse graph(UUID actorId,
                                         UUID workspaceId,
                                         UUID repositoryId,
                                         UUID documentId,
                                         Integer depth) {
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MEMBER);

        int safeDepth = depth == null ? 2 : Math.min(Math.max(1, depth), MAX_DEPTH);

        // ── The documents of the graph ──────────────────────────────────
        List<Document> documents = resolveDocuments(workspaceId, repositoryId, documentId);
        if (documents.isEmpty()) {
            return new DependencyGraphResponse(workspaceId, List.of(), List.of());
        }
        Map<UUID, Document> documentsById = new HashMap<>();
        for (Document document : documents) {
            documentsById.put(document.getId(), document);
        }

        // ── The links of those documents ────────────────────────────────
        List<CodeDocumentLink> links =
                linkRepository.findAllByDocumentIdIn(documentsById.keySet());

        // ── The code entities those links point at ─────────────────────
        Set<UUID> entityIds = new LinkedHashSet<>();
        for (CodeDocumentLink link : links) {
            entityIds.add(link.getCodeEntityId());
        }
        Map<UUID, CodeEntity> entitiesById = new HashMap<>();
        if (!entityIds.isEmpty()) {
            for (CodeEntity entity : codeEntityRepository.findAllById(entityIds)) {
                entitiesById.put(entity.getId(), entity);
            }
        }

        // ── The repositories and modules involved ───────────────────────
        Set<UUID> repositoryIds = new LinkedHashSet<>();
        for (Document document : documents) {
            if (document.getRepositoryId() != null) {
                repositoryIds.add(document.getRepositoryId());
            }
        }
        for (CodeEntity entity : entitiesById.values()) {
            repositoryIds.add(entity.getRepositoryId());
        }
        if (repositoryId != null) {
            repositoryIds.retainAll(Set.of(repositoryId));
        }
        Map<UUID, Repository> repositoriesById = new HashMap<>();
        if (!repositoryIds.isEmpty()) {
            for (Repository repository : repositoryRepository.findAllById(repositoryIds)) {
                repositoriesById.put(repository.getId(), repository);
            }
        }
        Map<UUID, List<RepositoryModule>> modulesByRepository = new HashMap<>();
        for (UUID repoId : repositoriesById.keySet()) {
            modulesByRepository.put(repoId,
                    moduleRepository.findByRepositoryIdOrderByModulePath(repoId));
        }

        // ── Assemble ────────────────────────────────────────────────────
        Map<String, DependencyNode> nodes = new LinkedHashMap<>();
        List<DependencyEdge> edges = new ArrayList<>();

        for (Document document : documents) {
            nodes.put(DependencyNode.id(DependencyNode.Kind.DOCUMENT, document.getId()),
                    documentNode(document));
        }

        for (CodeDocumentLink link : links) {
            Document document = documentsById.get(link.getDocumentId());
            CodeEntity entity = entitiesById.get(link.getCodeEntityId());
            if (document == null || entity == null) {
                // A link whose target has disappeared (the commit was
                // re-indexed and the entity id changed) is simply not
                // part of the graph — it must not produce a dangling
                // edge.
                continue;
            }
            String documentNodeId =
                    DependencyNode.id(DependencyNode.Kind.DOCUMENT, document.getId());
            String entityNodeId =
                    DependencyNode.id(DependencyNode.Kind.CODE_ENTITY, entity.getId());

            nodes.computeIfAbsent(entityNodeId, id -> entityNode(entity));
            nodes.computeIfAbsent(documentNodeId, id -> documentNode(document));

            edges.add(new DependencyEdge(
                    documentNodeId,
                    entityNodeId,
                    edgeKindOf(link),
                    link.getLinkKind(),
                    link.getConfidence()));

            addContainment(nodes, edges, entity, repositoriesById, modulesByRepository);
        }

        // A repository that only holds modules but no document still
        // shows up, so the containment chain is complete.
        for (Repository repository : repositoriesById.values()) {
            nodes.computeIfAbsent(
                    DependencyNode.id(DependencyNode.Kind.REPOSITORY, repository.getId()),
                    id -> repositoryNode(repository));
        }

        // ── Apply the depth limit ───────────────────────────────────────
        GraphSubset subset = applyDepth(nodes, edges, documentId, safeDepth);

        return new DependencyGraphResponse(workspaceId, subset.nodes(), subset.edges());
    }

    /**
     * The documents the graph is built from.
     *
     * <p>A {@code documentId} narrows the graph to that document and
     * its neighbourhood; without one the whole workspace is used (up to
     * the node cap).
     */
    private List<Document> resolveDocuments(UUID workspaceId,
                                             UUID repositoryId,
                                             UUID documentId) {
        if (documentId != null) {
            Document document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new NotFoundException("Document not found"));
            if (!document.getWorkspaceId().equals(workspaceId)) {
                throw new NotFoundException("Document not found in this workspace");
            }
            return List.of(document);
        }
        List<Document> documents = repositoryId != null
                ? documentRepository.findAllByWorkspaceIdAndRepositoryId(workspaceId, repositoryId)
                : documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
        if (documents.size() > MAX_DOCUMENTS) {
            documents = documents.subList(0, MAX_DOCUMENTS);
        }
        return documents;
    }

    /**
     * A {@code linkKind} of {@code GENERATED} (or anything that is not
     * explicitly a mere reference) means the document documents the
     * entity; a plain {@code REFERENCE} only means the document
     * mentions it.
     */
    private String edgeKindOf(CodeDocumentLink link) {
        String linkKind = link.getLinkKind();
        if (linkKind == null) {
            return DependencyEdge.Kind.REFERENCES;
        }
        return switch (linkKind.toUpperCase(java.util.Locale.ROOT)) {
            case "DOCUMENTS", "DOCUMENTED", "GENERATED", "DESCRIBES" ->
                    DependencyEdge.Kind.DOCUMENTS;
            default -> DependencyEdge.Kind.REFERENCES;
        };
    }

    /**
     * Add the containment chain of an entity: repository → module →
     * entity.
     *
     * <p>Only the edges that are missing are added, so an entity that
     * several documents link to does not get the same containment edge
     * twice.
     */
    private void addContainment(Map<String, DependencyNode> nodes,
                                List<DependencyEdge> edges,
                                CodeEntity entity,
                                Map<UUID, Repository> repositoriesById,
                                Map<UUID, List<RepositoryModule>> modulesByRepository) {
        Repository repository = repositoriesById.get(entity.getRepositoryId());
        if (repository == null) {
            return;
        }
        String repositoryNodeId =
                DependencyNode.id(DependencyNode.Kind.REPOSITORY, repository.getId());
        nodes.computeIfAbsent(repositoryNodeId, id -> repositoryNode(repository));

        String entityNodeId =
                DependencyNode.id(DependencyNode.Kind.CODE_ENTITY, entity.getId());
        nodes.computeIfAbsent(entityNodeId, id -> entityNode(entity));

        // The module that covers the file of the entity — the deepest
        // one, because modules can be nested.
        RepositoryModule module = coveringModule(
                modulesByRepository.getOrDefault(entity.getRepositoryId(), List.of()),
                entity.getFilePath());
        if (module == null) {
            // No module matches: the entity belongs to the repository
            // directly.
            addEdgeIfAbsent(edges, repositoryNodeId, entityNodeId, DependencyEdge.Kind.CONTAINS);
            return;
        }

        String moduleNodeId = DependencyNode.id(
                DependencyNode.Kind.MODULE, module.getRepositoryId() + ":" + module.getModulePath());
        nodes.computeIfAbsent(moduleNodeId, id -> moduleNode(module));

        addEdgeIfAbsent(edges, repositoryNodeId, moduleNodeId, DependencyEdge.Kind.CONTAINS);
        addEdgeIfAbsent(edges, moduleNodeId, entityNodeId, DependencyEdge.Kind.CONTAINS);
    }

    /**
     * The deepest module whose path prefix covers the file.
     *
     * <p>"Deepest" means the longest path prefix: when a repository
     * has both {@code /src} and {@code /src/auth}, a file in
     * {@code src/auth} belongs to {@code /src/auth}.
     */
    private RepositoryModule coveringModule(List<RepositoryModule> modules, String filePath) {
        RepositoryModule best = null;
        for (RepositoryModule module : modules) {
            if (!module.covers(filePath)) {
                continue;
            }
            if (best == null || module.getModulePath().length() > best.getModulePath().length()) {
                best = module;
            }
        }
        return best;
    }

    private void addEdgeIfAbsent(List<DependencyEdge> edges,
                                 String from,
                                 String to,
                                 String kind) {
        for (DependencyEdge edge : edges) {
            if (edge.from().equals(from) && edge.to().equals(to) && edge.kind().equals(kind)) {
                return;
            }
        }
        edges.add(new DependencyEdge(from, to, kind, null, null));
    }

    // =================================================================
    // The impact analysis
    // =================================================================

    /**
     * Compute the impact of changing one code entity.
     *
     * @param actorId   the caller; has to be a member of the workspace
     * @param workspaceId the workspace the entity belongs to
     * @param entityId  the entity that is about to change
     */
    @Transactional(readOnly = true)
    public ImpactAnalysisResponse impact(UUID actorId,
                                         UUID workspaceId,
                                         UUID entityId) {
        workspaceService.requireRole(actorId, workspaceId, WorkspaceRole.MEMBER);

        CodeEntity entity = codeEntityRepository.findById(entityId)
                .orElseThrow(() -> new NotFoundException("Code entity not found"));

        Repository repository = repositoryRepository.findById(entity.getRepositoryId())
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (!repository.getWorkspaceId().equals(workspaceId)) {
            throw new NotFoundException("Code entity not found in this workspace");
        }

        // ── The documents that document the entity ──────────────────────
        // This is the inverse of the graph: the graph walks document →
        // entity, the impact analysis walks entity → document.
        List<CodeDocumentLink> links = linkRepository.findAllByCodeEntityId(entityId);
        List<ImpactAnalysisResponse.ImpactedDocument> impactedDocuments = new ArrayList<>();
        Set<UUID> documentIds = new LinkedHashSet<>();
        if (!links.isEmpty()) {
            Map<UUID, Document> documentsById = new HashMap<>();
            for (Document document : documentRepository.findAllById(
                    links.stream().map(CodeDocumentLink::getDocumentId)
                            .distinct().toList())) {
                documentsById.put(document.getId(), document);
            }
            for (CodeDocumentLink link : links) {
                Document document = documentsById.get(link.getDocumentId());
                if (document == null) {
                    continue;
                }
                documentIds.add(document.getId());
                impactedDocuments.add(new ImpactAnalysisResponse.ImpactedDocument(
                        document.getId(),
                        document.getTitle(),
                        document.getSlug(),
                        document.getDocType(),
                        document.getStatus() == null ? null : document.getStatus().name(),
                        document.getRepositoryId(),
                        link.getLinkKind(),
                        link.getConfidence()));
            }
        }

        // ── The entities that move with it ──────────────────────────────
        String modulePath = modulePathOf(entity, repository.getId());
        List<ImpactAnalysisResponse.RelatedEntity> relatedEntities = relatedEntities(entity, modulePath);

        // ── The drift alerts of the affected documents ──────────────────
        List<ImpactAnalysisResponse.ImpactedDriftAlert> driftAlerts = new ArrayList<>();
        for (UUID documentId : documentIds) {
            for (DriftAlert alert : driftAlertRepository
                    .findAllByDocumentIdOrderByDetectedAtDesc(documentId)) {
                driftAlerts.add(new ImpactAnalysisResponse.ImpactedDriftAlert(
                        alert.getId(),
                        alert.getDocumentId(),
                        alert.getSeverity() == null ? null : alert.getSeverity().name(),
                        alert.getDriftKind() == null ? null : alert.getDriftKind().name(),
                        alert.getTitle(),
                        alert.getResolutionStatus() == null
                                ? null : alert.getResolutionStatus().name(),
                        alert.getDetectedAt()));
            }
        }

        // ── The pull requests currently open on the repository ──────────
        List<ImpactAnalysisResponse.ImpactedPullRequest> pullRequests = new ArrayList<>();
        for (PullRequest pullRequest : pullRequestRepository
                .findByRepositoryIdOrderByUpdatedAtDesc(repository.getId())) {
            if (pullRequest.getState() != PullRequest.State.OPEN) {
                continue;
            }
            pullRequests.add(new ImpactAnalysisResponse.ImpactedPullRequest(
                    pullRequest.getId(),
                    pullRequest.getGithubPrNumber(),
                    pullRequest.getTitle(),
                    pullRequest.getState() == null ? null : pullRequest.getState().name(),
                    pullRequest.getAuthorLogin(),
                    pullRequest.getHeadBranch(),
                    pullRequest.getHtmlUrl(),
                    pullRequest.getUpdatedAt()));
        }

        return new ImpactAnalysisResponse(
                new ImpactAnalysisResponse.ImpactedEntity(
                        entity.getId(),
                        entity.getRepositoryId(),
                        entity.getQualifiedName(),
                        entity.getSimpleName(),
                        entity.getEntityType(),
                        entity.getFilePath(),
                        entity.getStartLine(),
                        entity.getEndLine(),
                        modulePath,
                        entity.getLanguage()),
                impactedDocuments,
                relatedEntities.stream().sorted(byRelation()).toList(),
                driftAlerts,
                pullRequests);
    }

    /**
     * The entities that would be affected by a change to the given
     * one: the other entities of the same file, then the entities of
     * the same module.
     */
    private List<ImpactAnalysisResponse.RelatedEntity> relatedEntities(CodeEntity entity,
                                                                       String modulePath) {
        List<ImpactAnalysisResponse.RelatedEntity> related = new ArrayList<>();

        // Same file — the tightest relation.
        for (CodeEntity sibling : codeEntityRepository.findByFilePathIn(List.of(entity.getFilePath()))) {
            if (sibling.getId().equals(entity.getId())) {
                continue;
            }
            if (!sibling.getRepositoryId().equals(entity.getRepositoryId())) {
                continue;
            }
            related.add(new ImpactAnalysisResponse.RelatedEntity(
                    sibling.getId(),
                    sibling.getQualifiedName(),
                    sibling.getEntityType(),
                    sibling.getFilePath(),
                    sibling.getStartLine(),
                    "SAME_FILE"));
        }

        if (modulePath == null) {
            return related;
        }

        // Same module — the entities of the files the module covers.
        for (CodeEntity sibling : codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(
                entity.getRepositoryId())) {
            if (sibling.getId().equals(entity.getId())) {
                continue;
            }
            if (related.stream().anyMatch(r -> r.id().equals(sibling.getId()))) {
                continue;
            }
            if (!coversModule(modulePath, sibling.getFilePath())) {
                continue;
            }
            related.add(new ImpactAnalysisResponse.RelatedEntity(
                    sibling.getId(),
                    sibling.getQualifiedName(),
                    sibling.getEntityType(),
                    sibling.getFilePath(),
                    sibling.getStartLine(),
                    "SAME_MODULE"));
        }
        return related;
    }

    /**
     * The module of an entity, or {@code null} when the repository has
     * no module covering its file.
     */
    private String modulePathOf(CodeEntity entity, UUID repositoryId) {
        List<RepositoryModule> modules =
                moduleRepository.findByRepositoryIdOrderByModulePath(repositoryId);
        RepositoryModule module = coveringModule(modules, entity.getFilePath());
        return module == null ? null : module.getModulePath();
    }

    /** Does the module path cover the file? */
    private boolean coversModule(String modulePath, String filePath) {
        if (modulePath == null || filePath == null) {
            return false;
        }
        String prefix = modulePath.startsWith("/") ? modulePath.substring(1) : modulePath;
        String file = filePath.replace('\\', '/');
        while (file.startsWith("/")) {
            file = file.substring(1);
        }
        if (prefix.isEmpty()) {
            return true;
        }
        return file.equals(prefix) || file.startsWith(prefix + "/");
    }

    // =================================================================
    // Node projections
    // =================================================================

    private DependencyNode documentNode(Document document) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("docType", document.getDocType());
        meta.put("slug", document.getSlug());
        if (document.getRepositoryId() != null) {
            meta.put("repositoryId", document.getRepositoryId().toString());
        }
        if (document.getStatus() != null) {
            meta.put("status", document.getStatus().name());
        }
        return new DependencyNode(
                DependencyNode.id(DependencyNode.Kind.DOCUMENT, document.getId()),
                DependencyNode.Kind.DOCUMENT,
                document.getTitle(),
                document.getDocType(),
                meta);
    }

    private DependencyNode entityNode(CodeEntity entity) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("repositoryId", entity.getRepositoryId().toString());
        meta.put("entityType", entity.getEntityType());
        meta.put("filePath", entity.getFilePath());
        meta.put("qualifiedName", entity.getQualifiedName());
        if (entity.getStartLine() != null) {
            meta.put("startLine", String.valueOf(entity.getStartLine()));
        }
        return new DependencyNode(
                DependencyNode.id(DependencyNode.Kind.CODE_ENTITY, entity.getId()),
                DependencyNode.Kind.CODE_ENTITY,
                entity.getSimpleName(),
                entity.getFilePath(),
                meta);
    }

    private DependencyNode moduleNode(RepositoryModule module) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("modulePath", module.getModulePath());
        meta.put("repositoryId", module.getRepositoryId().toString());
        meta.put("autoUpdateEnabled", String.valueOf(module.isAutoUpdateEnabled()));
        return new DependencyNode(
                DependencyNode.id(DependencyNode.Kind.MODULE,
                        module.getRepositoryId() + ":" + module.getModulePath()),
                DependencyNode.Kind.MODULE,
                module.getDisplayName() == null || module.getDisplayName().isBlank()
                        ? module.getModulePath()
                        : module.getDisplayName(),
                module.getModulePath(),
                meta);
    }

    private DependencyNode repositoryNode(Repository repository) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("fullName", repository.getFullName());
        meta.put("defaultBranch", repository.getDefaultBranch());
        if (repository.getStatus() != null) {
            meta.put("status", repository.getStatus().name());
        }
        return new DependencyNode(
                DependencyNode.id(DependencyNode.Kind.REPOSITORY, repository.getId()),
                DependencyNode.Kind.REPOSITORY,
                repository.getFullName(),
                repository.getDefaultBranch(),
                meta);
    }

    // =================================================================
    // Depth
    // =================================================================

    /**
     * The result of applying the depth limit.
     */
    private record GraphSubset(List<DependencyNode> nodes, List<DependencyEdge> edges) {
    }

    /**
     * Cut the graph down to the requested depth.
     *
     * <p>The depth is measured in <em>levels of the dependency
     * chain</em>, not in raw hops:
     *
     * <pre>
     *   level 0 — the document
     *   level 1 — the code entities the document documents
     *   level 2 — the module of those entities
     *   level 3 — the repository of those modules
     * </pre>
     *
     * <p>Counting raw hops would not work: the containment edges run
     * the other way (repository → module → entity), so an undirected
     * traversal would reach the repository in one hop from the
     * entity and the depth would mean nothing.
     *
     * <p>Without a {@code documentId} the graph has no single root.
     * Every document is then a root of its own, and the depth decides
     * how far the containment chain is shown — which is exactly what
     * the reader of a workspace-wide graph wants.
     */
    private GraphSubset applyDepth(Map<String, DependencyNode> nodes,
                                   List<DependencyEdge> edges,
                                   UUID documentId,
                                   int depth) {
        // The level of a node is fixed by its kind, because the graph
        // is a chain of a fixed shape:
        //
        //   level 0 — the document
        //   level 1 — the code entities the document documents
        //   level 2 — the module of those entities
        //   level 3 — the repository of those modules
        //
        // Counting raw hops would not work: the containment edges run
        // the other way (repository → module → entity), so an
        // undirected traversal would reach the repository in one hop
        // from the entity and the depth would mean nothing.
        //
        // Without a {@code documentId} the graph has no single root.
        // Every document is then a root of its own, and the depth
        // decides how far the containment chain is shown — which is
        // exactly what the reader of a workspace-wide graph wants.
        Map<String, Integer> level = new HashMap<>();
        for (DependencyNode node : nodes.values()) {
            level.put(node.id(), levelOf(node.kind()));
        }

        Set<String> roots = new LinkedHashSet<>();
        if (documentId != null) {
            roots.add(DependencyNode.id(DependencyNode.Kind.DOCUMENT, documentId));
        } else {
            for (DependencyNode node : nodes.values()) {
                if (DependencyNode.Kind.DOCUMENT.equals(node.kind())) {
                    roots.add(node.id());
                }
            }
        }
        if (roots.isEmpty()) {
            // No document in the graph: there is nothing to root the
            // traversal at, so everything is kept (the node cap still
            // applies).
            return cap(nodes, edges);
        }

        Map<String, DependencyNode> keptNodes = new LinkedHashMap<>();
        for (DependencyNode node : nodes.values()) {
            if (level.get(node.id()) <= depth) {
                keptNodes.put(node.id(), node);
            }
        }
        List<DependencyEdge> keptEdges = edges.stream()
                .filter(edge -> keptNodes.containsKey(edge.from())
                        && keptNodes.containsKey(edge.to()))
                .toList();
        return cap(keptNodes, keptEdges);
    }

    /**
     * The level of a node kind in the dependency chain.
     */
    private static int levelOf(String kind) {
        return switch (kind) {
            case DependencyNode.Kind.DOCUMENT -> 0;
            case DependencyNode.Kind.CODE_ENTITY -> 1;
            case DependencyNode.Kind.MODULE -> 2;
            case DependencyNode.Kind.REPOSITORY -> 3;
            default -> 0;
        };
    }

    /**
     * Apply the node cap.
     *
     * <p>The nodes are kept in insertion order (documents first, then
     * the entities they link to), and an edge is only kept when both
     * of its ends survived.
     */
    private GraphSubset cap(Map<String, DependencyNode> nodes, List<DependencyEdge> edges) {
        if (nodes.size() <= MAX_NODES) {
            List<DependencyNode> ordered = new ArrayList<>(nodes.values());
            List<DependencyEdge> orderedEdges = edges.stream()
                    .filter(edge -> nodes.containsKey(edge.from()) && nodes.containsKey(edge.to()))
                    .toList();
            return new GraphSubset(ordered, orderedEdges);
        }

        Map<String, DependencyNode> limited = new LinkedHashMap<>();
        int count = 0;
        for (DependencyNode node : nodes.values()) {
            if (count >= MAX_NODES) {
                break;
            }
            limited.put(node.id(), node);
            count++;
        }
        List<DependencyEdge> limitedEdges = edges.stream()
                .filter(edge -> limited.containsKey(edge.from()) && limited.containsKey(edge.to()))
                .toList();
        return new GraphSubset(new ArrayList<>(limited.values()), limitedEdges);
    }

    /**
     * Sort the entities of the impact analysis so the reader sees the
     * closest relation first.
     */
    static Comparator<ImpactAnalysisResponse.RelatedEntity> byRelation() {
        return Comparator.comparingInt(entity -> switch (entity.relation()) {
            case "SAME_FILE" -> 0;
            case "SAME_MODULE" -> 1;
            default -> 2;
        });
    }

    /**
     * Helper for the tests: the ids of the documents of a graph.
     */
    public static Set<UUID> documentIdsOf(DependencyGraphResponse graph) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (DependencyNode node : graph.nodes()) {
            if (DependencyNode.Kind.DOCUMENT.equals(node.kind())
                    && node.id().startsWith("document:")) {
                ids.add(UUID.fromString(node.id().substring("document:".length())));
            }
        }
        return ids;
    }

    /**
     * Helper for the tests: the ids of the code entities of a graph.
     */
    public static Set<UUID> entityIdsOf(DependencyGraphResponse graph) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (DependencyNode node : graph.nodes()) {
            if (DependencyNode.Kind.CODE_ENTITY.equals(node.kind())
                    && node.id().startsWith("entity:")) {
                ids.add(UUID.fromString(node.id().substring("entity:".length())));
            }
        }
        return ids;
    }
}
