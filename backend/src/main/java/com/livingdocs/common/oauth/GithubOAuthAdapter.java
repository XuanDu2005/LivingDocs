package com.livingdocs.common.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.livingdocs.common.exception.OAuthExchangeException;
import com.livingdocs.modules.user.model.OAuthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OAuth adapter for GitHub. Hits:
 *   * https://github.com/login/oauth/authorize    (start)
 *   * https://github.com/login/oauth/access_token (code → token)
 *   * https://api.github.com/user                 (profile)
 *   * https://api.github.com/user/emails          (verified primary email)
 *
 * <p>GitHub's /user endpoint may not return a public email. When that
 * happens we fall back to /user/emails and pick the primary + verified
 * one.
 */
@Component
public class GithubOAuthAdapter implements OAuthProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(GithubOAuthAdapter.class);
    private static final String AUTH_URL  = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_URL  = "https://api.github.com/user";
    private static final String EMAILS_URL = "https://api.github.com/user/emails";

    private final OAuthProperties.Provider cfg;
    private final RestClient http;

    public GithubOAuthAdapter(OAuthProperties properties) {
        this.cfg = properties.getGithub();
        this.http = RestClient.create();
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GITHUB;
    }

    @Override
    public String buildAuthorizationUrl(String state) {
        String scope = String.join(" ", cfg.getScopes());
        return AUTH_URL
                + "?response_type=code"
                + "&client_id=" + enc(cfg.getClientId())
                + "&redirect_uri=" + enc(cfg.getRedirectUri())
                + "&scope=" + enc(scope)
                + "&state=" + enc(state)
                + "&allow_signup=true";
    }

    @Override
    public OAuthUserInfo exchangeAndFetchUser(String code) {
        String accessToken = exchangeCodeForToken(code);
        JsonNode user = http.get()
                .uri(USER_URL)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "livingdocs-backend")
                .retrieve()
                .body(JsonNode.class);
        if (user == null) {
            throw new OAuthExchangeException("GitHub /user returned empty body");
        }
        String idText = textOrNull(user, "id");
        if (idText == null) {
            throw new OAuthExchangeException("GitHub /user missing id");
        }
        String login = textOrNull(user, "login");
        String name  = textOrNull(user, "name");
        String displayName = (name != null && !name.isBlank()) ? name : login;
        String avatar = textOrNull(user, "avatar_url");

        // Email handling: prefer the public one, otherwise fetch /user/emails.
        String email = textOrNull(user, "email");
        boolean emailVerified = !"null".equals(textOrNull(user, "email"));  // public email is always verified
        if (email == null) {
            JsonNode emails = http.get()
                    .uri(EMAILS_URL)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "livingdocs-backend")
                    .retrieve()
                    .body(JsonNode.class);
            if (emails != null && emails.isArray()) {
                for (JsonNode e : emails) {
                    if (e.path("primary").asBoolean(false) && e.path("verified").asBoolean(false)) {
                        email = textOrNull(e, "email");
                        emailVerified = true;
                        break;
                    }
                }
                if (email == null) {
                    for (JsonNode e : emails) {
                        if (e.path("verified").asBoolean(false)) {
                            email = textOrNull(e, "email");
                            emailVerified = true;
                            break;
                        }
                    }
                }
            }
        }
        if (email == null) {
            // Last resort: derive from login. Marked unverified so we
            // know to ask the user to fix it later.
            email = login == null ? null : (login + "@users.noreply.github.com");
            emailVerified = false;
        }
        log.info("GitHub userinfo id={} email={}", idText, email);
        return new OAuthUserInfo(OAuthProvider.GITHUB, idText, email, emailVerified, displayName, avatar);
    }

    private String exchangeCodeForToken(String code) {
        try {
            JsonNode body = http.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("Accept", "application/json")
                    .body(formBody(Map.of(
                            "code", code,
                            "client_id", cfg.getClientId(),
                            "client_secret", cfg.getClientSecret(),
                            "redirect_uri", cfg.getRedirectUri()
                    )))
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new OAuthExchangeException("GitHub token endpoint returned empty body");
            }
            String token = textOrNull(body, "access_token");
            if (token == null) {
                throw new OAuthExchangeException("GitHub token endpoint did not return access_token: "
                        + body.toString());
            }
            return token;
        } catch (OAuthExchangeException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthExchangeException("Failed to exchange code with GitHub", e);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String formBody(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
    }
}
