package com.livingdocs.common.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * OAuth adapter for Google. Hits the standard endpoints documented at
 * https://developers.google.com/identity/protocols/oauth2/web-server
 */
@Component
public class GoogleOAuthAdapter implements OAuthProviderAdapter {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthAdapter.class);
    private static final String AUTH_URL  = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    private final OAuthProperties.Provider cfg;
    private final ObjectMapper objectMapper;
    private final RestClient http;

    public GoogleOAuthAdapter(OAuthProperties properties, ObjectMapper objectMapper) {
        this.cfg = properties.getGoogle();
        this.objectMapper = objectMapper;
        this.http = RestClient.create();
    }

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
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
                + "&prompt=select_account";
    }

    @Override
    public OAuthUserInfo exchangeAndFetchUser(String code) {
        String accessToken = exchangeCodeForToken(code);
        JsonNode payload = http.get()
                .uri(USERINFO_URL)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(JsonNode.class);
        if (payload == null) {
            throw new OAuthExchangeException("Google /userinfo returned empty body");
        }
        String sub   = textOrNull(payload, "sub");
        String email = textOrNull(payload, "email");
        boolean emailVerified = payload.path("email_verified").asBoolean(false);
        String name  = textOrNull(payload, "name");
        String pic   = textOrNull(payload, "picture");
        if (sub == null || email == null) {
            throw new OAuthExchangeException("Google /userinfo missing required fields");
        }
        log.info("Google userinfo sub={} email={}", sub, email);
        return new OAuthUserInfo(OAuthProvider.GOOGLE, sub, email, emailVerified, name, pic);
    }

    private String exchangeCodeForToken(String code) {
        try {
            JsonNode body = http.post()
                    .uri(TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody(Map.of(
                            "code", code,
                            "client_id", cfg.getClientId(),
                            "client_secret", cfg.getClientSecret(),
                            "redirect_uri", cfg.getRedirectUri(),
                            "grant_type", "authorization_code"
                    )))
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw new OAuthExchangeException("Google token endpoint returned empty body");
            }
            String token = textOrNull(body, "access_token");
            if (token == null) {
                throw new OAuthExchangeException("Google token endpoint did not return access_token: "
                        + body.toString());
            }
            return token;
        } catch (OAuthExchangeException e) {
            throw e;
        } catch (Exception e) {
            throw new OAuthExchangeException("Failed to exchange code with Google", e);
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
