package com.livingdocs.modules.github.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.github.config.GithubProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the repository-wide drift scan always reporting
 * "the code has not changed since the document was written".
 *
 * <p>GitHub's compare endpoint
 * ({@code GET /repos/{o}/{r}/compare/{base}...{head}}) answers with an
 * <em>object</em>:
 *
 * <pre>
 * { "status": "ahead", "ahead_by": 3, "total_commits": 3,
 *   "commits": [ ... ], "files": [ { "filename": "...", ... } ] }
 * </pre>
 *
 * <p>The client read the body as if it were a bare array of files, so
 * every comparison produced an empty file list. An empty list is
 * indistinguishable from "the two revisions are identical", and the
 * drift resolver turned it into the skip reason "the code has not
 * changed since the document was written" — for every document of
 * every repository, no matter how much the code had actually moved on.
 *
 * <p>This test serves the real shape of the response and asserts that
 * the changed files come through.
 */
class RestGithubClientCompareCommitsTest {

    private HttpServer server;
    private GithubProperties properties;

    @BeforeEach
    void startServer() throws Exception {
        String body = """
                {
                  "status": "ahead",
                  "ahead_by": 3,
                  "behind_by": 0,
                  "total_commits": 3,
                  "commits": [
                    {"sha": "a1", "message": "one"},
                    {"sha": "a2", "message": "two"},
                    {"sha": "a3", "message": "three"}
                  ],
                  "files": [
                    {"filename": "src/App.tsx", "status": "modified",
                     "additions": 10, "deletions": 2, "changes": 12,
                     "patch": "@@ -1 +1 @@"},
                    {"filename": "src/utils/table.ts", "status": "added",
                     "additions": 40, "deletions": 0, "changes": 40},
                    {"filename": "docs/README.md", "status": "modified",
                     "additions": 1, "deletions": 1, "changes": 2}
                  ]
                }
                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/o/r/compare", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        properties = new GithubProperties();
        properties.getApi().setBaseUrl(
                "http://localhost:" + server.getAddress().getPort());
        properties.getApi().setTimeoutMs(10_000);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void compareCommitsReadsTheFilesOfTheComparisonObject() {
        RestGithubClient client = new RestGithubClient(properties, new ObjectMapper());

        List<GithubPullRequestFile> files =
                client.compareCommits("token", "o", "r", "base-sha", "head-sha");

        assertEquals(3, files.size());
        assertEquals("src/App.tsx", files.get(0).path());
        assertEquals("modified", files.get(0).status());
        assertEquals(10L, files.get(0).additions());
        assertEquals("src/utils/table.ts", files.get(1).path());
        assertEquals("added", files.get(1).status());
        assertEquals("docs/README.md", files.get(2).path());
    }
}
