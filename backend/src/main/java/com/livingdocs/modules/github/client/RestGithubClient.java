package com.livingdocs.modules.github.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.github.config.GithubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link GithubClient} that talks to {@code api.github.com}
 * via {@link WebClient}.
 *
 * <p>Activated when {@code github.sandbox=false}. Handles:
 * <ul>
 *   <li>OAuth code exchange ({@code POST /login/oauth/access_token}).</li>
 *   <li>Authenticated user lookup ({@code GET /user}).</li>
 *   <li>User repository listing ({@code GET /user/repos}).</li>
 *   <li>Pull request listing ({@code GET /repos/{o}/{r}/pulls}).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "github", name = "sandbox", havingValue = "false")
public class RestGithubClient implements GithubClient {

    private static final Logger log = LoggerFactory.getLogger(RestGithubClient.class);

    private final GithubProperties properties;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public RestGithubClient(GithubProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getApi().getBaseUrl())
                .defaultHeader(HttpHeaders.USER_AGENT, properties.getApi().getUserAgent())
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .build();
    }

    @Override
    public GithubTokenResponse exchangeCode(String code, String state) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getOauth().getClientId());
        form.add("client_secret", properties.getOauth().getClientSecret());
        form.add("code", code);
        if (state != null) {
            form.add("state", state);
        }
        try {
            String body = webClient.post()
                    .uri("https://github.com/login/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(properties.getApi().getTimeoutMs()));
            JsonNode json = objectMapper.readTree(body);
            if (json.has("error")) {
                return new GithubTokenResponse(
                        null, null, null,
                        json.path("error").asText(),
                        json.path("error_description").asText(null));
            }
            return new GithubTokenResponse(
                    json.path("access_token").asText(),
                    json.path("token_type").asText("bearer"),
                    json.path("scope").asText(null),
                    null, null);
        } catch (WebClientResponseException ex) {
            throw new GithubClientException(
                    "OAuth exchange failed: " + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            throw new GithubClientException("OAuth exchange failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public GithubUser getCurrentUser(String accessToken) {
        try {
            GithubUser user = webClient.get()
                    .uri("/user")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(GithubUser.class)
                    .block(Duration.ofMillis(properties.getApi().getTimeoutMs()));
            if (user == null) {
                throw new GithubClientException("GitHub returned an empty user payload");
            }
            return user;
        } catch (WebClientResponseException ex) {
            throw new GithubClientException(
                    "GET /user failed: " + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            throw new GithubClientException("GET /user failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<GithubRepositorySummary> listUserRepositories(String accessToken, int perPage) {
        try {
            JsonNode arr = webClient.get()
                    .uri(uri -> uri.path("/user/repos")
                            .queryParam("per_page", Math.max(1, Math.min(perPage, 100)))
                            .queryParam("sort", "updated")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(properties.getApi().getTimeoutMs()));
            List<GithubRepositorySummary> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode r : arr) {
                    out.add(new GithubRepositorySummary(
                            r.path("id").asLong(),
                            r.path("name").asText(),
                            r.path("full_name").asText(),
                            r.path("owner").path("login").asText(),
                            r.path("default_branch").asText("main"),
                            r.path("html_url").asText(null),
                            r.path("description").asText(null),
                            r.path("private").asBoolean(false),
                            r.path("archived").asBoolean(false),
                            r.path("disabled").asBoolean(false)));
                }
            }
            return out;
        } catch (WebClientResponseException ex) {
            throw new GithubClientException(
                    "GET /user/repos failed: " + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (Exception ex) {
            throw new GithubClientException("GET /user/repos failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<GithubPullRequest> listPullRequests(String accessToken, String owner, String repo, String state) {
        try {
            JsonNode arr = webClient.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/pulls")
                            .queryParam("state", state == null ? "all" : state)
                            .queryParam("per_page", 50)
                            .build(owner, repo))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(properties.getApi().getTimeoutMs()));
            List<GithubPullRequest> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode pr : arr) {
                    List<String> labels = new ArrayList<>();
                    JsonNode labelsNode = pr.path("labels");
                    if (labelsNode.isArray()) {
                        for (JsonNode l : labelsNode) {
                            labels.add(l.path("name").asText());
                        }
                    }
                    out.add(new GithubPullRequest(
                            pr.path("number").asLong(),
                            pr.path("title").asText(""),
                            pr.path("state").asText("open"),
                            pr.path("user").path("login").asText(null),
                            pr.path("head").path("ref").asText(""),
                            pr.path("base").path("ref").asText(""),
                            pr.path("head").path("sha").asText(""),
                            pr.path("html_url").asText(null),
                            pr.path("draft").asBoolean(false),
                            parseDate(pr.path("created_at").asText(null)),
                            parseDate(pr.path("updated_at").asText(null)),
                            parseDate(pr.path("closed_at").asText(null)),
                            parseDate(pr.path("merged_at").asText(null)),
                            labels));
                }
            }
            return out;
        } catch (WebClientResponseException ex) {
            throw new GithubClientException(
                    "GET /repos/" + owner + "/" + repo + "/pulls failed: " + ex.getStatusCode(), ex);
        } catch (Exception ex) {
            throw new GithubClientException("GET /repos/" + owner + "/" + repo + "/pulls failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public GithubRepositorySummary fetchRepositoryByUrl(String accessToken, String url) {
        // Parse owner/name from URL
        String owner;
        String repo;
        try {
            java.net.URI uri = new java.net.URI(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                throw new GithubClientException("Invalid GitHub URL: " + url);
            }
            // Remove leading/trailing slashes
            path = path.replaceAll("^/+|/+$", "");
            String[] parts = path.split("/");
            if (parts.length < 2) {
                throw new GithubClientException(
                        "Invalid GitHub URL format. Expected: https://github.com/owner/repo");
            }
            owner = parts[0];
            repo = parts[1];
        } catch (Exception e) {
            throw new GithubClientException("Invalid GitHub URL: " + url, e);
        }

        try {
            JsonNode r = webClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMillis(properties.getApi().getTimeoutMs()));
            if (r == null) {
                throw new GithubClientException("GitHub returned an empty repository payload");
            }
            return new GithubRepositorySummary(
                    r.path("id").asLong(),
                    r.path("name").asText(),
                    r.path("full_name").asText(),
                    r.path("owner").path("login").asText(),
                    r.path("default_branch").asText("main"),
                    r.path("html_url").asText(null),
                    r.path("description").asText(null),
                    r.path("private").asBoolean(false),
                    r.path("archived").asBoolean(false),
                    r.path("disabled").asBoolean(false));
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new GithubClientException(
                        "Repository not found or not accessible: " + owner + "/" + repo);
            }
            throw new GithubClientException(
                    "GET /repos failed: " + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        } catch (GithubClientException e) {
            throw e;
        } catch (Exception ex) {
            throw new GithubClientException("GET /repos failed: " + ex.getMessage(), ex);
        }
    }

    private static OffsetDateTime parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME);
    }

    // ── Commits ───────────────────────────────────────────────────────────

    @Override
    public List<GithubCommit> listCommits(String accessToken, String owner, String repo,
                                          int perPage, OffsetDateTime since) {
        try {
            StringBuilder uri = new StringBuilder("/repos/").append(owner).append('/').append(repo)
                    .append("/commits?per_page=").append(Math.min(Math.max(perPage, 1), 100));
            if (since != null) {
                uri.append("&since=").append(since.format(DateTimeFormatter.ISO_DATE_TIME));
            }
            JsonNode arr = webClient.get()
                    .uri(uri.toString())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        if (ex.getStatusCode().value() == 401) {
                            throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                        }
                        return Mono.error(ex);
                    })
                    .block(Duration.ofSeconds(20));
            if (arr == null || !arr.isArray()) return List.of();
            List<GithubCommit> out = new ArrayList<>();
            for (JsonNode c : arr) {
                JsonNode commit = c.path("commit");
                JsonNode author = commit.path("author");
                String sha = c.path("sha").asText();
                out.add(new GithubCommit(
                        sha,
                        commit.path("message").asText(),
                        author.path("name").asText(null),
                        author.path("email").asText(null),
                        parseDate(author.path("date").asText(null)),
                        parseDate(commit.path("committer").path("date").asText(null)),
                        "https://github.com/" + owner + "/" + repo + "/commit/" + sha
                ));
            }
            return out;
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
            throw e;
        } catch (Exception e) {
            log.warn("listCommits failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public GithubRepositorySummary getRepository(String accessToken, String owner, String repo) {
        try {
            JsonNode r = webClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repo)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        if (ex.getStatusCode().value() == 401) {
                            throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                        }
                        return Mono.error(ex);
                    })
                    .block(Duration.ofSeconds(15));
            if (r == null) throw new GithubClientException("Empty response from /repos");
            return new GithubRepositorySummary(
                    r.path("id").asLong(),
                    r.path("name").asText(),
                    r.path("full_name").asText(),
                    r.path("owner").path("login").asText(),
                    r.path("default_branch").asText("main"),
                    r.path("html_url").asText(),
                    r.path("description").asText(null),
                    r.path("private").asBoolean(false),
                    r.path("archived").asBoolean(false),
                    r.path("fork").asBoolean(false)
            );
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
            throw e;
        } catch (Exception e) {
            throw new GithubClientException("getRepository failed: " + e.getMessage(), e);
        }
    }

    // ── Webhook management ───────────────────────────────────────────────

    @Override
    public Long createWebhook(String accessToken, String owner, String repo,
                              String targetUrl, String secret, List<String> events) {
        if (targetUrl == null || targetUrl.isBlank()) {
            throw new GithubClientException("Webhook target URL is required");
        }
        if (secret == null || secret.isBlank()) {
            throw new GithubClientException("Webhook secret is required");
        }
        List<String> safeEvents = (events == null || events.isEmpty())
                ? List.of("push", "pull_request", "ping")
                : events;
        String json = String.format(
                "{\"name\":\"web\",\"active\":true,\"events\":%s," +
                "\"config\":{\"url\":%s,\"content_type\":\"json\",\"secret\":%s,\"insecure_ssl\":\"0\"}}",
                toJsonArray(safeEvents),
                jsonString(targetUrl),
                jsonString(secret));
        try {
            JsonNode created = webClient.post()
                    .uri("/repos/{owner}/{repo}/hooks", owner, repo)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(json)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        if (ex.getStatusCode().value() == 401) {
                            throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                        }
                        if (ex.getStatusCode().value() == 404) {
                            throw new GithubClientException(
                                    "Cannot register webhook: repo " + owner + "/" + repo + " not found");
                        }
                        if (ex.getStatusCode().value() == 422) {
                            throw new GithubClientException(
                                    "GitHub rejected webhook registration (likely already exists or insufficient scope): "
                                            + ex.getResponseBodyAsString());
                        }
                        return Mono.error(ex);
                    })
                    .block(Duration.ofSeconds(15));
            if (created == null) {
                throw new GithubClientException("Empty response from POST /hooks");
            }
            long id = created.path("id").asLong();
            log.info("Registered GitHub webhook id={} on {}/{} -> {}",
                    id, owner, repo, targetUrl);
            return id;
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
            throw e;
        } catch (GithubClientException e) {
            throw e;
        } catch (Exception e) {
            throw new GithubClientException("createWebhook failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteWebhook(String accessToken, String owner, String repo, Long hookId) {
        if (hookId == null) return;
        try {
            webClient.delete()
                    .uri("/repos/{owner}/{repo}/hooks/{hookId}", owner, repo, hookId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        if (ex.getStatusCode().value() == 404) {
                            // Already gone — treat as success.
                            log.info("Webhook {} already absent on {}/{}", hookId, owner, repo);
                            return Mono.empty();
                        }
                        if (ex.getStatusCode().value() == 401) {
                            throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                        }
                        return Mono.error(ex);
                    })
                    .block(Duration.ofSeconds(10));
            log.info("Deleted GitHub webhook id={} on {}/{}", hookId, owner, repo);
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
            throw e;
        } catch (Exception e) {
            // Don't fail the user-facing disconnect on webhook cleanup failure —
            // the repository row is already marked DISCONNECTED. Log and move on.
            log.warn("Failed to delete GitHub webhook {} on {}/{}: {}",
                    hookId, owner, repo, e.getMessage());
        }
    }

    @Override
    public String createFileAsPullRequest(String accessToken, String owner, String repo,
                                           String baseBranch, String headBranch,
                                           String filePath, String content,
                                           String commitMessage, String prTitle, String prBody) {
        try {
            // 1. Look up the latest SHA of baseBranch so we can create headBranch from it.
            JsonNode baseRef = webClient.get()
                    .uri("/repos/{owner}/{repo}/git/ref/heads/{branch}", owner, repo, baseBranch)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        if (ex.getStatusCode().value() == 401) {
                            throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                        }
                        return Mono.error(ex);
                    })
                    .block(Duration.ofSeconds(15));
            if (baseRef == null) {
                throw new GithubClientException("Could not resolve base ref: " + baseBranch);
            }
            String baseSha = baseRef.path("object").path("sha").asText();
            if (baseSha.isEmpty()) {
                throw new GithubClientException("Base ref had no SHA: " + baseBranch);
            }

            // 2. Create the new head branch pointing at baseSha.
            String createRefBody = "{\"ref\":\"refs/heads/" + headBranch + "\",\"sha\":\"" + baseSha + "\"}";
            try {
                webClient.post()
                        .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(createRefBody)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .onErrorResume(WebClientResponseException.class, ex -> {
                            // 422 = ref already exists. That's fine — we'll just write to it.
                            if (ex.getStatusCode().value() == 422) return Mono.empty();
                            if (ex.getStatusCode().value() == 401) {
                                throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                            }
                            return Mono.error(ex);
                        })
                        .block(Duration.ofSeconds(15));
            } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
                throw e;
            } catch (Exception ignored) {
                // branch might already exist; continue
            }

            // 3. If the file already exists on the head branch, we need its current SHA to update.
            String existingFileSha = null;
            try {
                JsonNode existing = webClient.get()
                        .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, filePath)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .onErrorResume(WebClientResponseException.class, ex -> {
                            if (ex.getStatusCode().value() == 404) return Mono.empty();
                            if (ex.getStatusCode().value() == 401) {
                                throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                            }
                            return Mono.error(ex);
                        })
                        .block(Duration.ofSeconds(15));
                if (existing != null && existing.has("sha")) {
                    existingFileSha = existing.path("sha").asText();
                }
            } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
                throw e;
            } catch (Exception ignored) {
                // 404 = new file. Continue.
            }

            // 4. PUT the file content on the new branch.
            String encoded = java.util.Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder putBody = new StringBuilder();
            putBody.append("{\"message\":").append(jsonString(commitMessage));
            putBody.append(",\"content\":\"").append(encoded).append('"');
            if (existingFileSha != null) {
                putBody.append(",\"sha\":\"").append(existingFileSha).append('"');
            }
            putBody.append(",\"branch\":").append(jsonString(headBranch));
            putBody.append('}');

            webClient.put()
                    .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, filePath)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(putBody.toString())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        if (ex.getStatusCode().value() == 401) {
                            throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                        }
                        return Mono.error(ex);
                    })
                    .block(Duration.ofSeconds(20));

            // 5. Open the pull request.
            StringBuilder prBody_ = new StringBuilder();
            prBody_.append("{\"title\":").append(jsonString(prTitle));
            prBody_.append(",\"body\":").append(jsonString(prBody));
            prBody_.append(",\"head\":").append(jsonString(headBranch));
            prBody_.append(",\"base\":").append(jsonString(baseBranch));
            prBody_.append("}");

            JsonNode pr = webClient.post()
                    .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(prBody_.toString())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        if (ex.getStatusCode().value() == 401) {
                            throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                        }
                        return Mono.error(ex);
                    })
                    .block(Duration.ofSeconds(20));
            if (pr == null) {
                throw new GithubClientException("Empty response from POST /pulls");
            }
            String prUrl = pr.path("html_url").asText(pr.path("url").asText(""));
            log.info("Opened PR {} on {}/{} ({} -> {})", prUrl, owner, repo, headBranch, baseBranch);
            return prUrl;
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
            throw e;
        } catch (GithubClientException e) {
            throw e;
        } catch (Exception e) {
            throw new GithubClientException("createFileAsPullRequest failed: " + e.getMessage(), e);
        }
    }

    // ── Repository Contents ───────────────────────────────────────────────

    @Override
    public List<GithubRepositoryContent> listRepositoryContents(String accessToken, String owner, String repo,
                                                               String path, String ref) {
        try {
            String uri = "/repos/" + owner + "/" + repo + "/contents/" + (path == null || path.isBlank() ? "" : path);
            if (ref != null && !ref.isBlank()) {
                uri += "?ref=" + ref;
            }
            JsonNode arr = webClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        if (ex.getStatusCode().value() == 401) {
                            throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                        }
                        return Mono.error(ex);
                    })
                    .block(Duration.ofSeconds(15));
            if (arr == null || !arr.isArray()) return List.of();
            List<GithubRepositoryContent> out = new ArrayList<>();
            for (JsonNode item : arr) {
                JsonNode sizeNode = item.path("size");
                Long size = sizeNode.isMissingNode() || sizeNode.isNull() ? null : sizeNode.asLong();
                out.add(new GithubRepositoryContent(
                        item.path("name").asText(),
                        item.path("path").asText(),
                        item.path("type").asText("file"),
                        size,
                        item.path("download_url").asText(null)
                ));
            }
            return out;
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
            throw e;
        } catch (Exception e) {
            log.warn("listRepositoryContents failed: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public String getFileContent(String accessToken, String owner, String repo, String path, String ref) {
        try {
            String uri = "/repos/" + owner + "/" + repo + "/contents/" + path;
            if (ref != null && !ref.isBlank()) {
                uri += "?ref=" + ref;
            }
            JsonNode file = webClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .onErrorResume(WebClientResponseException.class, ex -> {
                        if (ex.getStatusCode().value() == 401) {
                            throw new com.livingdocs.modules.github.exception.GithubTokenInvalidException();
                        }
                        if (ex.getStatusCode().value() == 404) return Mono.empty();
                        return Mono.error(ex);
                    })
                    .block(Duration.ofSeconds(15));
            if (file == null || !file.has("content")) return null;
            // GitHub returns base64-encoded content
            String encoded = file.path("content").asText().replaceAll("\\s", "");
            byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (com.livingdocs.modules.github.exception.GithubTokenInvalidException e) {
            throw e;
        } catch (Exception e) {
            log.warn("getFileContent failed for {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(values.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}