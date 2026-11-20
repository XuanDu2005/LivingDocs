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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the "Nạp tất cả" (ingest all) action failing
 * silently.
 *
 * <p>The reactive {@code WebClient} buffers the response body in memory
 * and Spring's default limit for that is 256&nbsp;KB. A single page of 50
 * pull requests for an active repository is routinely larger than that,
 * so the request blew up with
 * {@code DataBufferLimitException: Exceeded limit on max bytes to buffer
 * : 262144} and the user got an error instead of any pull requests.
 *
 * <p>This test serves a payload that is deliberately larger than the old
 * default limit and asserts that the client still parses it.
 */
class RestGithubClientBufferLimitTest {

    private HttpServer server;
    private GithubProperties properties;

    @BeforeEach
    void startServer() throws Exception {
        // ~120 PRs with long titles/labels → comfortably over 256 KB.
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 120; i++) {
            if (i > 0) {
                json.append(',');
            }
            String padding = "x".repeat(2000);
            json.append("{\"number\":").append(i)
                    .append(",\"title\":\"").append(padding).append('"')
                    .append(",\"state\":\"open\",\"user\":{\"login\":\"octocat\"}")
                    .append(",\"base\":{\"ref\":\"main\"}")
                    .append(",\"head\":{\"ref\":\"feature/").append(padding)
                    .append("\",\"sha\":\"abc").append(i).append("\"}")
                    .append(",\"html_url\":\"https://github.com/o/r/pull/").append(i).append('"')
                    .append(",\"draft\":false")
                    .append(",\"labels\":[{\"name\":\"").append(padding).append("\"}]")
                    .append('}');
        }
        json.append(']');
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(body.length > 262_144,
                "The test payload must exceed the old 256 KB default limit");

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/repos/o/r/pulls", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
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
    void listPullRequestsHandlesResponseLargerThanDefaultBufferLimit() {
        RestGithubClient client = new RestGithubClient(properties, new ObjectMapper());

        List<GithubPullRequest> prs =
                client.listPullRequests("token", "o", "r", "all");

        assertEquals(120, prs.size());
    }
}
