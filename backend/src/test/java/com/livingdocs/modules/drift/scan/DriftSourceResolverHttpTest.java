package com.livingdocs.modules.drift.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.code.model.CodeEntity;
import com.livingdocs.modules.code.repository.CodeEntityRepository;
import com.livingdocs.modules.document.model.Document;
import com.livingdocs.modules.document.repository.DocumentRepository;
import com.livingdocs.modules.github.client.RestGithubClient;
import com.livingdocs.modules.github.config.GithubProperties;
import com.livingdocs.modules.github.model.Repository;
import com.livingdocs.modules.github.repository.CommitRepository;
import com.livingdocs.modules.github.repository.PullRequestRepository;
import com.livingdocs.modules.github.repository.RepositoryRepository;
import com.livingdocs.modules.github.service.GithubCredentialService;
import com.livingdocs.modules.link.repository.CodeDocumentLinkRepository;
import com.livingdocs.modules.version.model.DocumentVersion;
import com.livingdocs.modules.version.repository.DocumentVersionRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The repository-wide drift scan, end to end over HTTP.
 *
 * <p>This test wires the <em>real</em> {@link RestGithubClient} to a
 * local HTTP server that answers like GitHub does, and drives it
 * through the {@link DriftSourceResolver}. That combination is what
 * makes the difference between "the test passes" and "the feature
 * works": the bug it protects against lived in the JSON parsing of
 * GitHub's response, and a mocked client would have hidden it — the
 * mock would have returned exactly what the broken client returned.
 *
 * <p>The scenario is the one a user hits in practice: a document was
 * written against an old revision of the code, the code has moved on,
 * and the scan has to notice.
 */
class DriftSourceResolverHttpTest {

    private HttpServer server;
    private final AtomicReference<String> comparedPath = new AtomicReference<>();

    private static final String OLD_SHA = "old-sha";
    private static final String MAIN = "main";

    @BeforeEach
    void startServer() throws Exception {
        // The compare endpoint answers with an object whose "files"
        // array names the file that changed.
        String compare = """
                {
                  "status": "ahead",
                  "ahead_by": 1,
                  "behind_by": 0,
                  "total_commits": 1,
                  "commits": [{"sha": "head-sha"}],
                  "files": [
                    {"filename": "src/utils/formatDate.ts", "status": "modified",
                     "additions": 8, "deletions": 1, "changes": 9}
                  ]
                }
                """;

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/acme/app/compare", exchange -> {
            comparedPath.set(exchange.getRequestURI().getPath());
            respond(exchange, compare);
        });
        server.createContext("/repos/acme/app/contents/src/utils/formatDate.ts", exchange -> {
            // The same path is requested at two revisions: the anchor
            // gets the old content, the head gets the new one.
            String query = exchange.getRequestURI().getQuery();
            String body = ("ref=" + OLD_SHA).equals(query)
                    ? "export function formatDate(date: Date): string { return ''; }"
                    : "export function formatDate(date: Date | string): string { return ''; } "
                      + "export function formatTime(date: Date): string { return ''; }";
            respond(exchange, "{\"content\":\"" + base64(body) + "\",\"encoding\":\"base64\"}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * The document is compared against the revision it was written for
     * and against the current head — and the changed file is found.
     *
     * <p>Before the fix the compare response was read as if it were a
     * bare array, so the changed-file list came back empty and the
     * scan answered "the code has not changed since the document was
     * written" for every document of every repository.
     */
    @Test
    void aDocumentWhoseCodeChangedIsComparedInsteadOfSkipped() {
        UUID workspaceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        Repository repository = Repository.create(workspaceId, 1L, "acme", "app", MAIN,
                "https://github.com/acme/app", null, false, UUID.randomUUID());
        UUID repositoryId = repository.getId();

        CodeEntityRepository codeEntityRepository = mock(CodeEntityRepository.class);
        CodeDocumentLinkRepository linkRepository = mock(CodeDocumentLinkRepository.class);
        DocumentVersionRepository versionRepository = mock(DocumentVersionRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RepositoryRepository repositoryRepository = mock(RepositoryRepository.class);
        CommitRepository commitRepository = mock(CommitRepository.class);

        // The document covers exactly the file that changed.
        CodeEntity entity = new CodeEntity(repositoryId, "head-sha", "typescript", "FUNCTION",
                "formatDate", "formatDate", "src/utils/formatDate.ts", 1, 5,
                "export function formatDate(date: Date): string", null, null);
        when(codeEntityRepository.findAllByRepositoryIdOrderByIngestedAtDesc(repositoryId))
                .thenReturn(List.of(entity));

        // The document was written against the commit "old-sha".
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getSourceCommitSha()).thenReturn(OLD_SHA);
        when(versionRepository.findAllByDocumentIdOrderByVersionNumberDesc(documentId))
                .thenReturn(List.of(version));

        Document doc = mock(Document.class);
        when(doc.getId()).thenReturn(documentId);
        when(doc.getWorkspaceId()).thenReturn(workspaceId);
        when(doc.getRepositoryId()).thenReturn(repositoryId);
        when(doc.getTitle()).thenReturn("Formatting Helpers");
        when(doc.getUpdatedAt()).thenReturn(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        when(doc.getCreatedAt()).thenReturn(OffsetDateTime.parse("2026-01-15T10:00:00Z"));

        GithubProperties properties = new GithubProperties();
        properties.getApi().setBaseUrl(
                "http://localhost:" + server.getAddress().getPort());
        properties.getApi().setTimeoutMs(10_000);

        DriftSourceResolver resolver = new DriftSourceResolver(
                new RestGithubClient(properties, new ObjectMapper()),
                codeEntityRepository, linkRepository, versionRepository,
                documentRepository, repositoryRepository,
                mock(PullRequestRepository.class), commitRepository,
                mock(GithubCredentialService.class));

        DriftSourceResolver.Comparison comparison =
                resolver.resolve(repository, "token", doc, null);

        assertFalse(comparison.isSkipped(),
                "The document must be compared, not skipped: " + comparison.skipped());
        assertEquals(1, comparison.filesBefore().size());
        assertEquals(1, comparison.filesAfter().size());
        assertEquals("src/utils/formatDate.ts", comparison.filesBefore().get(0).path());
        assertEquals("src/utils/formatDate.ts", comparison.filesAfter().get(0).path());
        assertEquals(OLD_SHA, comparison.beforeRef());
        assertEquals(MAIN, comparison.afterRef());
        assertTrue(comparedPath.get() != null && comparedPath.get().contains(OLD_SHA + "..." + MAIN),
                "The comparison must span the anchor and the head, was: " + comparedPath.get());
    }
}
