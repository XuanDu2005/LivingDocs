package com.livingdocs.modules.drift.scan;

import com.livingdocs.modules.ai.client.AiDtos;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.client.GithubClient;
import com.livingdocs.modules.github.client.GithubPullRequestFile;
import com.livingdocs.modules.github.model.Commit;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.CommitRepository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubOAuthService;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a repository-wide drift scan decides <em>what to compare</em>.
 *
 * <p>Three things matter here, and each of them was wrong at some
 * point:
 *
 * <ul>
 *   <li><b>The anchor.</b> A document that does not record the commit
 *       it was written against must still be comparable — otherwise
 *       every repository-level document is skipped and the scan
 *       reports "nothing to compare" for a repository that clearly has
 *       drift.</li>
 *   <li><b>The scope.</b> Only the files that actually differ can
 *       hold drift. Comparing a document against every file of the
 *       repository produces noise and costs one HTTP request per
 *       file.</li>
 *   <li><b>The honesty.</b> A failing GitHub call must not look like
 *       "nothing changed".</li>
 * </ul>
 */
class DriftSourceResolverTest {

    private GithubClient githubClient;
    private CodeEntityRepository codeEntityRepository;
    private CodeDocumentLinkRepository linkRepository;
    private DocumentVersionRepository versionRepository;
    private DocumentRepository documentRepository;
    private RepositoryRepository repositoryRepository;
    private CommitRepository commitRepository;
    private DriftSourceResolver resolver;

    private UUID workspaceId;
    private UUID repositoryId;
    private UUID documentId;
    private Repository repository;

    private static final String TOKEN = "gh-token";
    private static final String MAIN = "main";

    @BeforeEach
    void setUp() {
        githubClient = mock(GithubClient.class);
        codeEntityRepository = mock(CodeEntityRepository.class);
        linkRepository = mock(CodeDocumentLinkRepository.class);
        versionRepository = mock(DocumentVersionRepository.class);
        documentRepository = mock(DocumentRepository.class);
        repositoryRepository = mock(RepositoryRepository.class);
        commitRepository = mock(CommitRepository.class);

        resolver = new DriftSourceResolver(
                githubClient,
                codeEntityRepository,
                linkRepository,
                versionRepository,
                documentRepository,
                repositoryRepository,
                mock(PullRequestRepository.class),
                commitRepository,
                mock(GithubOAuthService.class));

        workspaceId = UUID.randomUUID();
        documentId = UUID.randomUUID();

        repository = Repository.create(workspaceId, 1L, "acme", "app", MAIN,
                "https://github.com/acme/app", null, false, UUID.randomUUID());
        // The repository generates its own id; the test has to use
        // that one, because a document is only "of" the repository
        // when the two ids match.
        repositoryId = repository.getId();

        // A document linked to one indexed file of the repository.
        when(linkRepository.findAllByDocumentId(documentId)).thenReturn(List.of());
        when(codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId))
                .thenReturn(List.of(anEntity("src/App.java")));
    }

    private CodeEntity anEntity(String filePath) {
        return new CodeEntity(repositoryId, "sha", "java", "CLASS",
                "acme.App", "App", filePath, 1, 10, "class App", null, null);
    }

    private Document aDocument(OffsetDateTime writtenAt) {
        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(documentId);
        when(doc.getWorkspaceId()).thenReturn(workspaceId);
        when(doc.getRepositoryId()).thenReturn(repositoryId);
        when(doc.getTitle()).thenReturn("Architecture");
        when(doc.getUpdatedAt()).thenReturn(writtenAt);
        when(doc.getCreatedAt()).thenReturn(writtenAt);
        return doc;
    }

    private void theVersionRecordsNoCommit() {
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getSourceCommitSha()).thenReturn(null);
        when(versionRepository.findAllByDocumentIdOrderByVersionNumberDesc(documentId))
                .thenReturn(List.of(version));
    }

    // =================================================================
    // the anchor
    // =================================================================

    /**
     * The version of the document records the commit it was written
     * against. That commit is the anchor, and nothing else is
     * consulted.
     */
    @Test
    void theCommitOfTheVersionIsTheAnchor() {
        Document doc = aDocument(OffsetDateTime.now());
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getSourceCommitSha()).thenReturn("anchor-sha");
        when(versionRepository.findAllByDocumentIdOrderByVersionNumberDesc(documentId))
                .thenReturn(List.of(version));

        when(commitRepository.findFirstByRepositoryIdAndAuthoredAtLessThanEqualOrderByAuthoredAtDesc(
                eq(repositoryId), any())).thenReturn(Optional.empty());
        when(githubClient.compareCommits(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        resolver.resolve(repository, TOKEN, doc, null);

        verify(githubClient).compareCommits(TOKEN, "acme", "app", "anchor-sha", MAIN);
    }

    /**
     * The document does not record a commit — a repository-level
     * overview written by a path that does not stamp the commit. The
     * code as it stood when the document was last written is then the
     * anchor: the closest the platform can offer.
     */
    @Test
    void withoutARecordedCommitTheCodeOfThatMomentIsTheAnchor() {
        OffsetDateTime writtenAt = OffsetDateTime.parse("2026-01-15T10:00:00Z");
        Document doc = aDocument(writtenAt);
        theVersionRecordsNoCommit();

        Commit commit = new Commit(repositoryId, "at-write-time", "m", "a", "e",
                writtenAt, writtenAt, "u", MAIN);
        when(commitRepository.findFirstByRepositoryIdAndAuthoredAtLessThanEqualOrderByAuthoredAtDesc(
                eq(repositoryId), eq(writtenAt))).thenReturn(Optional.of(commit));
        when(githubClient.compareCommits(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        resolver.resolve(repository, TOKEN, doc, null);

        verify(githubClient).compareCommits(TOKEN, "acme", "app", "at-write-time", MAIN);
    }

    /**
     * Nothing was committed before the document existed. The earliest
     * revision the platform knows of is then the only thing it can
     * speak about.
     */
    @Test
    void withoutAnyEarlierCommitTheEarliestOneIsUsed() {
        Document doc = aDocument(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        theVersionRecordsNoCommit();

        when(commitRepository.findFirstByRepositoryIdAndAuthoredAtLessThanEqualOrderByAuthoredAtDesc(
                eq(repositoryId), any())).thenReturn(Optional.empty());
        Commit earliest = new Commit(repositoryId, "earliest", "m", "a", "e",
                OffsetDateTime.parse("2025-12-01T00:00:00Z"),
                OffsetDateTime.parse("2025-12-01T00:00:00Z"), "u", MAIN);
        when(commitRepository.findFirstByRepositoryIdOrderByAuthoredAtAsc(repositoryId))
                .thenReturn(Optional.of(earliest));
        when(githubClient.compareCommits(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        resolver.resolve(repository, TOKEN, doc, null);

        verify(githubClient).compareCommits(TOKEN, "acme", "app", "earliest", MAIN);
    }

    /**
     * A repository the platform has never seen a commit of has no
     * anchor at all, and the document is skipped with a reason.
     */
    @Test
    void withoutAnyCommitTheDocumentIsSkipped() {
        Document doc = aDocument(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        theVersionRecordsNoCommit();

        when(commitRepository.findFirstByRepositoryIdAndAuthoredAtLessThanEqualOrderByAuthoredAtDesc(
                eq(repositoryId), any())).thenReturn(Optional.empty());
        when(commitRepository.findFirstByRepositoryIdOrderByAuthoredAtAsc(repositoryId))
                .thenReturn(Optional.empty());

        DriftSourceResolver.Comparison comparison =
                resolver.resolve(repository, TOKEN, doc, null);

        assertTrue(comparison.isSkipped());
        assertTrue(comparison.skipped().contains("no earlier revision"));
        verify(githubClient, never()).compareCommits(anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    // =================================================================
    // the scope
    // =================================================================

    /**
     * Only the files that differ are compared. A document that talks
     * about files nobody touched has no drift to report.
     */
    @Test
    void onlyTheChangedFilesOfTheDocumentAreCompared() {
        Document doc = aDocument(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        theVersionRecordsNoCommit();
        when(commitRepository.findFirstByRepositoryIdAndAuthoredAtLessThanEqualOrderByAuthoredAtDesc(
                eq(repositoryId), any())).thenReturn(Optional.of(
                new Commit(repositoryId, "before", "m", "a", "e",
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"), "u", MAIN)));

        when(githubClient.compareCommits(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        new GithubPullRequestFile("src/App.java", "modified", null, 1L, 1L, 2L),
                        new GithubPullRequestFile("src/Unrelated.java", "modified", null, 1L, 1L, 2L)));
        when(githubClient.getFileContent(TOKEN, "acme", "app", "src/App.java", "before"))
                .thenReturn("class App { }");
        when(githubClient.getFileContent(TOKEN, "acme", "app", "src/App.java", MAIN))
                .thenReturn("class App { void run() {} }");

        DriftSourceResolver.Comparison comparison =
                resolver.resolve(repository, TOKEN, doc, null);

        assertFalse(comparison.isSkipped());
        assertEquals(1, comparison.filesBefore().size());
        assertEquals(1, comparison.filesAfter().size());
        assertEquals("src/App.java", comparison.filesBefore().get(0).path());
        // The unrelated file is never downloaded.
        verify(githubClient, never()).getFileContent(anyString(), anyString(), anyString(),
                eq("src/Unrelated.java"), anyString());
    }

    /**
     * The changed files do not touch what the document covers. There is
     * no drift to report, and the document is skipped with a reason
     * rather than compared against files it says nothing about.
     */
    @Test
    void aDocumentThatCoversNothingChangedIsSkipped() {
        Document doc = aDocument(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        theVersionRecordsNoCommit();
        when(commitRepository.findFirstByRepositoryIdAndAuthoredAtLessThanEqualOrderByAuthoredAtDesc(
                eq(repositoryId), any())).thenReturn(Optional.of(
                new Commit(repositoryId, "before", "m", "a", "e",
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"), "u", MAIN)));

        when(githubClient.compareCommits(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        new GithubPullRequestFile("docs/README.md", "modified", null, 1L, 1L, 2L)));

        DriftSourceResolver.Comparison comparison =
                resolver.resolve(repository, TOKEN, doc, null);

        assertTrue(comparison.isSkipped());
        assertTrue(comparison.skipped().contains("None of the code"));
    }

    /**
     * Nothing changed at all: the document is up to date, and the scan
     * says so instead of calling the AI.
     */
    @Test
    void anUnchangedRepositorySkipsTheDocument() {
        Document doc = aDocument(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        theVersionRecordsNoCommit();
        when(commitRepository.findFirstByRepositoryIdAndAuthoredAtLessThanEqualOrderByAuthoredAtDesc(
                eq(repositoryId), any())).thenReturn(Optional.of(
                new Commit(repositoryId, "before", "m", "a", "e",
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                        OffsetDateTime.parse("2026-01-01T00:00:00Z"), "u", MAIN)));

        when(githubClient.compareCommits(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(List.of());

        DriftSourceResolver.Comparison comparison =
                resolver.resolve(repository, TOKEN, doc, null);

        assertTrue(comparison.isSkipped());
        assertTrue(comparison.skipped().contains("has not changed"));
    }

    /**
     * A document with no code at all — a hand-written policy page — has
     * nothing to compare, and the scan says so.
     */
    @Test
    void aDocumentWithoutIndexedCodeIsSkipped() {
        Document doc = aDocument(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        theVersionRecordsNoCommit();
        when(codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId))
                .thenReturn(List.of());

        DriftSourceResolver.Comparison comparison =
                resolver.resolve(repository, TOKEN, doc, null);

        assertTrue(comparison.isSkipped());
        assertTrue(comparison.skipped().contains("not linked to any indexed code"));
    }
}
