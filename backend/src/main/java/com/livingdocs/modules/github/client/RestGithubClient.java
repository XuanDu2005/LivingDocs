package com.livingdocs.modules.github.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.modules.github.config.GithubProperties;
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
                    .uri("/login/oauth/access_token")
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

    private static OffsetDateTime parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME);
    }
}