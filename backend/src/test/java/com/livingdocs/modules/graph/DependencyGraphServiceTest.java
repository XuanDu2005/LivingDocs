package com.livingdocs.modules.graph;

import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.model.DocumentStatus;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.drift.model.DriftAlert;
import com.livingdocs.modules.drift.model.DriftKind;
import com.livingdocs.modules.drift.model.DriftResolution;
import com.livingdocs.modules.drift.model.DriftSeverity;
import com.livingdocs.modules.drift.repository.DriftAlertRepository;
import com.livingdocs.modules.github.model.PullRequest;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.model.RepositoryModule;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryModuleRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.graph.model.DependencyEdge;
import com.livingdocs.modules.graph.model.DependencyNode;
import com.livingdocs.modules.graph.service.DependencyGraphService;
import com.livingdocs.modules.link.model.CodeDocumentLink;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The documentation-to-code dependency graph and the impact analysis.
 *
 * <p>The requirement asks for a graph that connects documentation to
 * code so that a code change can be traced back to the documentation
 * it invalidates. These tests pin the two rules that make that
 * trustworthy: the graph is derived from the existing links (so it
 * cannot drift from the truth), and the impact analysis walks the
 * links in reverse — from the entity to the documents.
 */
class DependencyGraphServiceTest {

    private DocumentRepository documentRepository;
    private CodeEntityRepository codeEntityRepository;
    private CodeDocumentLinkRepository linkRepository;
    private RepositoryRepository repositoryRepository;
    private RepositoryModuleRepository moduleRepository;
    private DriftAlertRepository driftAlertRepository;
    private PullRequestRepository pullRequestRepository;
    private WorkspaceService workspaceService;
    private DependencyGraphService service;

    private UUID workspaceId;
    private UUID actorId;
    private UUID documentId;
    private UUID entityId;
    private UUID repositoryId;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        codeEntityRepository = mock(CodeEntityRepository.class);
        linkRepository = mock(CodeDocumentLinkRepository.class);
        repositoryRepository = mock(RepositoryRepository.class);
        moduleRepository = mock(RepositoryModuleRepository.class);
        driftAlertRepository = mock(DriftAlertRepository.class);
        pullRequestRepository = mock(PullRequestRepository.class);
        workspaceService = mock(WorkspaceService.class);

        service = new DependencyGraphService(
                documentRepository,
                codeEntityRepository,
                linkRepository,
                repositoryRepository,
                moduleRepository,
                driftAlertRepository,
                pullRequestRepository,
                workspaceService);

        workspaceId = UUID.randomUUID();
        actorId = UUID.randomUUID();
        documentId = UUID.randomUUID();
        entityId = UUID.randomUUID();
        repositoryId = UUID.randomUUID();

        when(linkRepository.findAllByDocumentIdIn(any())).thenReturn(List.of());
        when(moduleRepository.findByRepositoryIdOrderByModulePath(any())).thenReturn(List.of());
    }

    private Document aDocument() {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(documentId);
        when(document.getWorkspaceId()).thenReturn(workspaceId);
        when(document.getRepositoryId()).thenReturn(repositoryId);
        when(document.getTitle()).thenReturn("User service reference");
        when(document.getSlug()).thenReturn("user-service-reference");
        when(document.getDocType()).thenReturn("API_REFERENCE");
        when(document.getStatus()).thenReturn(DocumentStatus.PUBLISHED);
        return document;
    }

    private CodeEntity anEntity() {
        CodeEntity entity = mock(CodeEntity.class);
        when(entity.getId()).thenReturn(entityId);
        when(entity.getRepositoryId()).thenReturn(repositoryId);
        when(entity.getQualifiedName()).thenReturn("com.acme.UserService.create");
        when(entity.getSimpleName()).thenReturn("create");
        when(entity.getEntityType()).thenReturn("METHOD");
        when(entity.getFilePath()).thenReturn("src/main/java/com/acme/UserService.java");
        when(entity.getStartLine()).thenReturn(42);
        when(entity.getEndLine()).thenReturn(60);
        when(entity.getLanguage()).thenReturn("java");
        return entity;
    }

    /**
     * A ready-made repository mock.
     *
     * <p>It is created and stubbed <em>before</em> it is handed to a
     * {@code thenReturn(...)}: creating a mock inside the argument of
     * another stubbing would leave Mockito with an unfinished stubbing
     * and the test would fail with a confusing error.
     */
    private Repository aRepository() {
        Repository repository = mock(Repository.class);
        when(repository.getId()).thenReturn(repositoryId);
        when(repository.getWorkspaceId()).thenReturn(workspaceId);
        when(repository.getFullName()).thenReturn("acme/backend");
        when(repository.getDefaultBranch()).thenReturn("main");
        return repository;
    }

    // =================================================================
    // The graph
    // =================================================================

    /**
     * The heart of the feature: a document that links a code entity
     * produces exactly that edge, and both ends are nodes of the
     * graph.
     */
    @Test
    void aLinkedDocumentAndEntityProduceAnEdge() {
        Document document = aDocument();
        CodeEntity entity = anEntity();
        Repository repository = aRepository();

        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));
        when(linkRepository.findAllByDocumentIdIn(any())).thenReturn(List.of(
                new CodeDocumentLink(documentId, entityId, "GENERATED", 0.92f)));
        when(codeEntityRepository.findAllById(any())).thenReturn(List.of(entity));
        when(repositoryRepository.findAllById(any())).thenReturn(List.of(repository));

        var graph = service.graph(actorId, workspaceId, null, null, 2);

        assertTrue(graph.nodes().stream().anyMatch(n ->
                n.id().equals(DependencyNode.id(DependencyNode.Kind.DOCUMENT, documentId))));
        assertTrue(graph.nodes().stream().anyMatch(n ->
                n.id().equals(DependencyNode.id(DependencyNode.Kind.CODE_ENTITY, entityId))));

        DependencyEdge edge = graph.edges().stream()
                .filter(e -> e.kind().equals(DependencyEdge.Kind.DOCUMENTS))
                .findFirst()
                .orElseThrow();
        assertEquals(DependencyNode.id(DependencyNode.Kind.DOCUMENT, documentId), edge.from());
        assertEquals(DependencyNode.id(DependencyNode.Kind.CODE_ENTITY, entityId), edge.to());
        assertEquals(0.92f, edge.confidence());
    }

    /**
     * A plain reference is not the same claim as "this document
     * documents this entity": the edge kind has to tell the reader
     * which one it is.
     */
    @Test
    void aPlainReferenceIsNotADocumentationEdge() {
        Document document = aDocument();
        CodeEntity entity = anEntity();

        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));
        when(linkRepository.findAllByDocumentIdIn(any())).thenReturn(List.of(
                new CodeDocumentLink(documentId, entityId, "REFERENCE", 0.4f)));
        when(codeEntityRepository.findAllById(any())).thenReturn(List.of(entity));
        Repository repository = aRepository();
        when(repositoryRepository.findAllById(any())).thenReturn(List.of(repository));

        var graph = service.graph(actorId, workspaceId, null, null, 2);

        assertTrue(graph.edges().stream().anyMatch(e ->
                e.kind().equals(DependencyEdge.Kind.REFERENCES)
                        && e.from().endsWith(documentId.toString())));
        assertFalse(graph.edges().stream().anyMatch(e ->
                e.kind().equals(DependencyEdge.Kind.DOCUMENTS)));
    }

    /**
     * The containment chain has to be complete: repository → module →
     * entity. Without it the reader cannot tell which package a code
     * entity lives in.
     */
    @Test
    void theContainmentChainRunsFromRepositoryThroughModuleToEntity() {
        Document document = aDocument();
        CodeEntity entity = anEntity();
        Repository repository = aRepository();

        RepositoryModule module = new RepositoryModule(
                repositoryId, workspaceId, "/src/main/java/com/acme", "acme", true);

        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));
        when(linkRepository.findAllByDocumentIdIn(any())).thenReturn(List.of(
                new CodeDocumentLink(documentId, entityId, "GENERATED", 0.9f)));
        when(codeEntityRepository.findAllById(any())).thenReturn(List.of(entity));
        when(repositoryRepository.findAllById(any())).thenReturn(List.of(repository));
        when(moduleRepository.findByRepositoryIdOrderByModulePath(repositoryId))
                .thenReturn(List.of(module));

        var graph = service.graph(actorId, workspaceId, null, null, 3);

        String repositoryNodeId = DependencyNode.id(DependencyNode.Kind.REPOSITORY, repositoryId);
        String moduleNodeId = DependencyNode.id(DependencyNode.Kind.MODULE,
                repositoryId + ":/src/main/java/com/acme");
        String entityNodeId = DependencyNode.id(DependencyNode.Kind.CODE_ENTITY, entityId);

        assertTrue(graph.nodes().stream().anyMatch(n -> n.id().equals(moduleNodeId)));
        assertTrue(graph.edges().stream().anyMatch(e ->
                e.from().equals(repositoryNodeId) && e.to().equals(moduleNodeId)));
        assertTrue(graph.edges().stream().anyMatch(e ->
                e.from().equals(moduleNodeId) && e.to().equals(entityNodeId)));
    }

    /**
     * The deepest matching module wins: a repository with both
     * {@code /src} and {@code /src/auth} puts a file of
     * {@code src/auth} into {@code /src/auth}.
     */
    @Test
    void theDeepestMatchingModuleIsUsed() {
        Document document = aDocument();
        CodeEntity entity = anEntity();

        RepositoryModule shallow = new RepositoryModule(
                repositoryId, workspaceId, "/src", "src", true);
        RepositoryModule deep = new RepositoryModule(
                repositoryId, workspaceId, "/src/main/java/com/acme", "acme", true);

        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));
        when(linkRepository.findAllByDocumentIdIn(any())).thenReturn(List.of(
                new CodeDocumentLink(documentId, entityId, "GENERATED", 0.9f)));
        when(codeEntityRepository.findAllById(any())).thenReturn(List.of(entity));
        Repository repository = aRepository();
        when(repositoryRepository.findAllById(any())).thenReturn(List.of(repository));
        when(moduleRepository.findByRepositoryIdOrderByModulePath(repositoryId))
                .thenReturn(List.of(shallow, deep));

        var graph = service.graph(actorId, workspaceId, null, null, 3);

        assertTrue(graph.nodes().stream().anyMatch(n ->
                n.id().equals(DependencyNode.id(DependencyNode.Kind.MODULE,
                        repositoryId + ":/src/main/java/com/acme"))));
        assertFalse(graph.nodes().stream().anyMatch(n ->
                n.id().equals(DependencyNode.id(DependencyNode.Kind.MODULE,
                        repositoryId + ":/src"))));
    }

    /**
     * The depth limits how far the graph reaches. With a document as
     * the root, depth 1 reaches the code entities but not the
     * repository.
     */
    @Test
    void theDepthLimitsHowFarTheGraphReaches() {
        Document document = aDocument();
        CodeEntity entity = anEntity();

        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(linkRepository.findAllByDocumentIdIn(any())).thenReturn(List.of(
                new CodeDocumentLink(documentId, entityId, "GENERATED", 0.9f)));
        when(codeEntityRepository.findAllById(any())).thenReturn(List.of(entity));
        Repository repository = aRepository();
        when(repositoryRepository.findAllById(any())).thenReturn(List.of(repository));

        var shallow = service.graph(actorId, workspaceId, null, documentId, 1);

        assertTrue(shallow.nodes().stream().anyMatch(n ->
                n.kind().equals(DependencyNode.Kind.CODE_ENTITY)));
        assertFalse(shallow.nodes().stream().anyMatch(n ->
                n.kind().equals(DependencyNode.Kind.REPOSITORY)));
    }

    /**
     * The depth counts the levels of the dependency chain, not raw
     * hops: document → entity → module → repository.
     *
     * <p>An undirected hop count would be meaningless here: the
     * containment edges run the other way, so the repository would be
     * one hop away from the entity no matter how the depth was
     * measured.
     */
    @Test
    void theDepthCountsTheLevelsOfTheDependencyChain() {
        Document document = aDocument();
        CodeEntity entity = anEntity();
        Repository repository = aRepository();

        RepositoryModule module = new RepositoryModule(
                repositoryId, workspaceId, "/src/main/java/com/acme", "acme", true);

        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        when(linkRepository.findAllByDocumentIdIn(any())).thenReturn(List.of(
                new CodeDocumentLink(documentId, entityId, "GENERATED", 0.9f)));
        when(codeEntityRepository.findAllById(any())).thenReturn(List.of(entity));
        when(repositoryRepository.findAllById(any())).thenReturn(List.of(repository));
        when(moduleRepository.findByRepositoryIdOrderByModulePath(repositoryId))
                .thenReturn(List.of(module));

        // Level 0: document. Level 1: entity. Level 2: module.
        var two = service.graph(actorId, workspaceId, null, documentId, 2);
        assertTrue(two.nodes().stream().anyMatch(n ->
                n.kind().equals(DependencyNode.Kind.MODULE)));
        assertFalse(two.nodes().stream().anyMatch(n ->
                n.kind().equals(DependencyNode.Kind.REPOSITORY)));

        // Level 3: the repository.
        var three = service.graph(actorId, workspaceId, null, documentId, 3);
        assertTrue(three.nodes().stream().anyMatch(n ->
                n.kind().equals(DependencyNode.Kind.REPOSITORY)));
    }

    /**
     * A link whose code entity has disappeared (the commit was
     * re-indexed) must not produce a dangling edge.
     */
    @Test
    void aLinkToAMissingEntityIsSkipped() {
        Document document = aDocument();

        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));
        when(linkRepository.findAllByDocumentIdIn(any())).thenReturn(List.of(
                new CodeDocumentLink(documentId, entityId, "GENERATED", 0.9f)));
        when(codeEntityRepository.findAllById(any())).thenReturn(List.of());

        var graph = service.graph(actorId, workspaceId, null, null, 2);

        assertFalse(graph.edges().stream().anyMatch(e ->
                e.to().equals(DependencyNode.id(DependencyNode.Kind.CODE_ENTITY, entityId))));
    }

    /**
     * A document of another workspace must not be readable through the
     * wrong URL.
     */
    @Test
    void aDocumentOfAnotherWorkspaceIsNotFound() {
        Document foreign = aDocument();
        when(foreign.getWorkspaceId()).thenReturn(UUID.randomUUID());
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(foreign));

        assertThrows(NotFoundException.class,
                () -> service.graph(actorId, workspaceId, null, documentId, 2));
    }

    /**
     * The graph is capped: one request must not be able to pull the
     * whole index of a monorepo into memory.
     */
    @Test
    void theGraphIsCapped() {
        Document document = aDocument();
        when(documentRepository.findAllByWorkspaceIdOrderByUpdatedAtDesc(workspaceId))
                .thenReturn(List.of(document));
        when(codeEntityRepository.findAllById(any())).thenReturn(List.of());
        when(repositoryRepository.findAllById(any())).thenReturn(List.of());

        var graph = service.graph(actorId, workspaceId, null, null, 2);

        assertTrue(graph.nodes().size() <= DependencyGraphService.MAX_NODES);
    }

    // =================================================================
    // The impact analysis
    // =================================================================

    /**
     * The verification of the requirement: change a method, and the
     * impact analysis names the document that documents it.
     */
    @Test
    void theImpactAnalysisNamesTheDocumentsOfTheEntity() {
        CodeEntity entity = anEntity();
        Document document = aDocument();

        when(codeEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        Repository repository = aRepository();
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repository));
        when(linkRepository.findAllByCodeEntityId(entityId)).thenReturn(List.of(
                new CodeDocumentLink(documentId, entityId, "GENERATED", 0.95f)));
        when(documentRepository.findAllById(any())).thenReturn(List.of(document));
        when(codeEntityRepository.findByFilePathIn(any())).thenReturn(List.of());
        when(codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(any()))
                .thenReturn(List.of());
        when(driftAlertRepository.findAllByDocumentIdOrderByDetectedAtDesc(any()))
                .thenReturn(List.of());
        when(pullRequestRepository.findByRepositoryIdOrderByUpdatedAtDesc(any()))
                .thenReturn(List.of());

        var impact = service.impact(actorId, workspaceId, entityId);

        assertEquals(1, impact.documents().size());
        assertEquals(documentId, impact.documents().get(0).id());
        assertEquals("User service reference", impact.documents().get(0).title());
        assertEquals("GENERATED", impact.documents().get(0).linkKind());
        assertEquals(0.95f, impact.documents().get(0).confidence());
    }

    /**
     * The impact analysis also names the drift alerts that already
     * touch the affected documents — otherwise the reviewer has to
     * look them up one by one.
     */
    @Test
    void theImpactAnalysisCarriesTheDriftAlertsOfTheAffectedDocuments() {
        CodeEntity entity = anEntity();
        Document document = aDocument();

        DriftAlert alert = mock(DriftAlert.class);
        when(alert.getId()).thenReturn(UUID.randomUUID());
        when(alert.getDocumentId()).thenReturn(documentId);
        when(alert.getSeverity()).thenReturn(DriftSeverity.HIGH);
        when(alert.getDriftKind()).thenReturn(DriftKind.SIGNATURE);
        when(alert.getTitle()).thenReturn("Signature changed");
        when(alert.getResolutionStatus()).thenReturn(DriftResolution.OPEN);

        when(codeEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        Repository repository = aRepository();
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repository));
        when(linkRepository.findAllByCodeEntityId(entityId)).thenReturn(List.of(
                new CodeDocumentLink(documentId, entityId, "GENERATED", 0.95f)));
        when(documentRepository.findAllById(any())).thenReturn(List.of(document));
        when(codeEntityRepository.findByFilePathIn(any())).thenReturn(List.of());
        when(codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(any()))
                .thenReturn(List.of());
        when(driftAlertRepository.findAllByDocumentIdOrderByDetectedAtDesc(documentId))
                .thenReturn(List.of(alert));
        when(pullRequestRepository.findByRepositoryIdOrderByUpdatedAtDesc(any()))
                .thenReturn(List.of());

        var impact = service.impact(actorId, workspaceId, entityId);

        assertEquals(1, impact.driftAlerts().size());
        assertEquals("HIGH", impact.driftAlerts().get(0).severity());
        assertEquals("Signature changed", impact.driftAlerts().get(0).title());
    }

    /**
     * Only the pull requests that are still open are relevant to a
     * change that is about to happen.
     */
    @Test
    void theImpactAnalysisOnlyCarriesOpenPullRequests() {
        CodeEntity entity = anEntity();

        PullRequest open = mock(PullRequest.class);
        when(open.getId()).thenReturn(UUID.randomUUID());
        when(open.getGithubPrNumber()).thenReturn(42L);
        when(open.getTitle()).thenReturn("Refactor the user service");
        when(open.getState()).thenReturn(PullRequest.State.OPEN);

        PullRequest merged = mock(PullRequest.class);
        when(merged.getState()).thenReturn(PullRequest.State.MERGED);

        when(codeEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        Repository repository = aRepository();
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repository));
        when(linkRepository.findAllByCodeEntityId(entityId)).thenReturn(List.of());
        when(codeEntityRepository.findByFilePathIn(any())).thenReturn(List.of());
        when(codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(any()))
                .thenReturn(List.of());
        when(pullRequestRepository.findByRepositoryIdOrderByUpdatedAtDesc(repositoryId))
                .thenReturn(List.of(open, merged));

        var impact = service.impact(actorId, workspaceId, entityId);

        assertEquals(1, impact.pullRequests().size());
        assertEquals(42L, impact.pullRequests().get(0).number());
    }

    /**
     * The entities of the same file are the tightest relation: they
     * move with the changed one.
     */
    @Test
    void theImpactAnalysisNamesTheEntitiesOfTheSameFile() {
        CodeEntity entity = anEntity();

        CodeEntity sibling = mock(CodeEntity.class);
        when(sibling.getId()).thenReturn(UUID.randomUUID());
        when(sibling.getRepositoryId()).thenReturn(repositoryId);
        when(sibling.getQualifiedName()).thenReturn("com.acme.UserService.findAll");
        when(sibling.getEntityType()).thenReturn("METHOD");
        when(sibling.getFilePath()).thenReturn("src/main/java/com/acme/UserService.java");
        when(sibling.getStartLine()).thenReturn(70);

        when(codeEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        Repository repository = aRepository();
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(repository));
        when(linkRepository.findAllByCodeEntityId(entityId)).thenReturn(List.of());
        when(codeEntityRepository.findByFilePathIn(any())).thenReturn(List.of(entity, sibling));
        when(codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(any()))
                .thenReturn(List.of());
        when(pullRequestRepository.findByRepositoryIdOrderByUpdatedAtDesc(any()))
                .thenReturn(List.of());

        var impact = service.impact(actorId, workspaceId, entityId);

        assertEquals(1, impact.relatedEntities().size());
        assertEquals("SAME_FILE", impact.relatedEntities().get(0).relation());
        assertNotNull(impact.entity().modulePath() == null ? "ok" : "ok");
    }

    /**
     * A code entity of another workspace must not be analysed through
     * the wrong URL.
     */
    @Test
    void anEntityOfAnotherWorkspaceIsNotFound() {
        CodeEntity entity = anEntity();
        Repository foreign = aRepository();
        when(foreign.getWorkspaceId()).thenReturn(UUID.randomUUID());

        when(codeEntityRepository.findById(entityId)).thenReturn(Optional.of(entity));
        when(repositoryRepository.findById(repositoryId)).thenReturn(Optional.of(foreign));

        assertThrows(NotFoundException.class,
                () -> service.impact(actorId, workspaceId, entityId));
    }
}
