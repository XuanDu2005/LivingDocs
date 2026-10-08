package com.livingdocs.modules.integrations.service;

import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.common.exception.NotFoundException;
import com.livingdocs.modules.audit.service.AuditLogService;
import com.livingdocs.modules.integrations.dto.ConnectIntegrationRequest;
import com.livingdocs.modules.integrations.dto.ConnectionDto;
import com.livingdocs.modules.integrations.dto.TestResponse;
import com.livingdocs.modules.integrations.model.IntegrationConnection;
import com.livingdocs.modules.integrations.model.IntegrationEvent;
import com.livingdocs.modules.integrations.model.IntegrationProvider;
import com.livingdocs.modules.integrations.model.IntegrationStatus;
import com.livingdocs.modules.integrations.repository.IntegrationConnectionRepository;
import com.livingdocs.modules.integrations.repository.IntegrationEventRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD + lifecycle operations for {@link IntegrationConnection}.
 *
 * <p>The connection row is the source of truth for the integrations
 * module: every webhook handler, the Slack notifier, and the admin UI
 * read through this service so audit logging and encryption live in a
 * single place.
 */
@Service
public class IntegrationConnectionService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConnectionService.class);
    private static final int EVENT_PAGE = 50;

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationEventRepository eventRepository;
    private final IntegrationCrypto crypto;
    private final AuditLogService auditLogService;
    private final WorkspaceService workspaceService;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public IntegrationConnectionService(IntegrationConnectionRepository connectionRepository,
                                        IntegrationEventRepository eventRepository,
                                        IntegrationCrypto crypto,
                                        AuditLogService auditLogService,
                                        WorkspaceService workspaceService,
                                        ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.eventRepository = eventRepository;
        this.crypto = crypto;
        this.auditLogService = auditLogService;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    // ---- Queries --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ConnectionDto> listAll() {
        return connectionRepository.findAllByOrderByConnectedAtDesc().stream()
                .map(ConnectionDto::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConnectionDto> listByWorkspace(UUID workspaceId) {
        return connectionRepository
                .findAllByWorkspaceIdOrderByConnectedAtDesc(workspaceId).stream()
                .map(ConnectionDto::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ConnectionDto> listByProvider(IntegrationProvider provider) {
        return connectionRepository
                .findAllByProviderOrderByConnectedAtDesc(provider).stream()
                .map(ConnectionDto::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public ConnectionDto get(UUID id) {
        return ConnectionDto.of(requireConnection(id));
    }

    @Transactional(readOnly = true)
    public IntegrationConnection requireConnection(UUID id) {
        return connectionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Integration connection not found"));
    }

    @Transactional(readOnly = true)
    public List<IntegrationConnection> findActiveByWorkspaceAndProvider(UUID workspaceId,
                                                                       IntegrationProvider provider) {
        return connectionRepository
                .findAllByWorkspaceIdAndProviderOrderByConnectedAtDesc(workspaceId, provider)
                .stream()
                .filter(c -> c.getStatus() == IntegrationStatus.ACTIVE)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<IntegrationEvent> recentEvents(int limit) {
        int clamped = Math.max(1, Math.min(limit, EVENT_PAGE));
        return eventRepository.findAllByOrderByReceivedAtDesc(PageRequest.of(0, clamped));
    }

    @Transactional(readOnly = true)
    public List<IntegrationEvent> recentEventsForConnection(UUID connectionId, int limit) {
        int clamped = Math.max(1, Math.min(limit, EVENT_PAGE));
        return eventRepository.findAllByConnectionIdOrderByReceivedAtDesc(
                connectionId, PageRequest.of(0, clamped));
    }

    // ---- Mutations ------------------------------------------------------

    @Transactional
    public ConnectionDto create(UUID actorId, String actorRoleCode,
                                ConnectIntegrationRequest req) {
        UUID workspaceId = req.workspaceId();
        if (workspaceId != null) {
            workspaceService.requireMember(actorId, workspaceId);
        }

        Optional<IntegrationConnection> existing = connectionRepository
                .findByProviderAndWorkspaceIdAndExternalAccount(
                        req.provider(), workspaceId, req.externalAccount());
        if (existing.isPresent()) {
            throw new BadRequestException(
                    "This account is already connected. Update the existing row instead.");
        }

        IntegrationConnection c = IntegrationConnection.create(
                req.provider(), workspaceId, req.displayName(), req.externalAccount());
        applySecrets(c, req);
        c.setBaseUrl(req.baseUrl());
        c.setScopes(req.scopes());
        c.setDefaultChannel(req.defaultChannel());

        IntegrationConnection saved = connectionRepository.save(c);

        auditLogService.record(actorId, actorRoleCode, "integration.connect",
                "integration", saved.getId().toString(), workspaceId,
                baseAuditPayload(saved));

        return ConnectionDto.of(saved);
    }

    @Transactional
    public ConnectionDto update(UUID actorId, String actorRoleCode, UUID id,
                                ConnectIntegrationRequest req) {
        IntegrationConnection c = requireConnection(id);
        UUID workspaceId = c.getWorkspaceId();
        if (workspaceId != null) {
            workspaceService.requireMember(actorId, workspaceId);
        }

        c.setDisplayName(req.displayName());
        c.setExternalAccount(req.externalAccount());
        c.setBaseUrl(req.baseUrl());
        c.setScopes(req.scopes());
        c.setDefaultChannel(req.defaultChannel());
        // Empty strings mean "leave unchanged" so the admin can re-submit
        // the form without erasing a token that was previously set.
        if (req.accessToken() != null && !req.accessToken().isBlank()) {
            c.setAccessTokenEncrypted(crypto.encrypt(req.accessToken()));
        }
        if (req.refreshToken() != null && !req.refreshToken().isBlank()) {
            c.setRefreshTokenEncrypted(crypto.encrypt(req.refreshToken()));
        }
        if (req.webhookSecret() != null && !req.webhookSecret().isBlank()) {
            c.setWebhookSecretEncrypted(crypto.encrypt(req.webhookSecret()));
        }

        IntegrationConnection saved = connectionRepository.save(c);
        auditLogService.record(actorId, actorRoleCode, "integration.update",
                "integration", saved.getId().toString(), workspaceId,
                baseAuditPayload(saved));
        return ConnectionDto.of(saved);
    }

    @Transactional
    public void revoke(UUID actorId, String actorRoleCode, UUID id) {
        IntegrationConnection c = requireConnection(id);
        UUID workspaceId = c.getWorkspaceId();
        if (workspaceId != null) {
            workspaceService.requireMember(actorId, workspaceId);
        }
        c.setStatus(IntegrationStatus.REVOKED);
        connectionRepository.save(c);
        auditLogService.record(actorId, actorRoleCode, "integration.revoke",
                "integration", c.getId().toString(), workspaceId,
                baseAuditPayload(c));
    }

    /**
     * Quick "is the stored token still valid?" probe. Implementation per
     * provider lives in {@link #probeProvider(IntegrationConnection)}.
     */
    @Transactional
    public TestResponse test(UUID actorId, String actorRoleCode, UUID id) {
        IntegrationConnection c = requireConnection(id);
        UUID workspaceId = c.getWorkspaceId();
        if (workspaceId != null) {
            workspaceService.requireMember(actorId, workspaceId);
        }
        TestResponse result;
        try {
            result = probeProvider(c);
        } catch (Exception ex) {
            log.warn("Integration {} ({}) health-check failed: {}",
                    c.getProvider(), c.getExternalAccount(), ex.getMessage());
            c.setStatus(IntegrationStatus.ERROR);
            connectionRepository.save(c);
            result = TestResponse.failure(ex.getMessage());
        }
        if (result.ok()) {
            c.setStatus(IntegrationStatus.ACTIVE);
            c.setLastUsedAt(OffsetDateTime.now());
            connectionRepository.save(c);
        }
        auditLogService.record(actorId, actorRoleCode,
                result.ok() ? "integration.test.ok" : "integration.test.fail",
                "integration", c.getId().toString(), workspaceId,
                Map.of("provider", c.getProvider().name(),
                        "externalAccount", c.getExternalAccount(),
                        "message", result.message() == null ? "" : result.message()));
        return result;
    }

    private void applySecrets(IntegrationConnection c, ConnectIntegrationRequest req) {
        if (req.accessToken() != null && !req.accessToken().isBlank()) {
            c.setAccessTokenEncrypted(crypto.encrypt(req.accessToken()));
        }
        if (req.refreshToken() != null && !req.refreshToken().isBlank()) {
            c.setRefreshTokenEncrypted(crypto.encrypt(req.refreshToken()));
        }
        if (req.webhookSecret() != null && !req.webhookSecret().isBlank()) {
            c.setWebhookSecretEncrypted(crypto.encrypt(req.webhookSecret()));
        }
    }

    private Map<String, Object> baseAuditPayload(IntegrationConnection c) {
        Map<String, Object> m = new HashMap<>();
        m.put("provider", c.getProvider().name());
        m.put("externalAccount", c.getExternalAccount());
        m.put("workspaceId", c.getWorkspaceId() == null ? "" : c.getWorkspaceId().toString());
        m.put("displayName", c.getDisplayName());
        m.put("status", c.getStatus().name());
        return m;
    }

    private TestResponse probeProvider(IntegrationConnection c) {
        String token = crypto.decrypt(c.getAccessTokenEncrypted());
        if (token == null || token.isBlank()) {
            return TestResponse.failure("No access token stored on this connection");
        }
        try {
            switch (c.getProvider()) {
                case SLACK -> {
                    Map<?, ?> body = restClient.get()
                            .uri("https://slack.com/api/auth.test")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .retrieve()
                            .body(Map.class);
                    boolean ok = body != null && Boolean.TRUE.equals(body.get("ok"));
                    String message = body == null ? "no response"
                            : (ok ? "Connected as " + body.get("user") : String.valueOf(body.get("error")));
                    return ok ? TestResponse.success(message)
                              : TestResponse.failure(message);
                }
                case GITHUB -> {
                    Map<?, ?> body = restClient.get()
                            .uri(resolveBase(c, "https://api.github.com") + "/user")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                            .retrieve()
                            .body(Map.class);
                    String login = body == null ? "unknown" : String.valueOf(body.get("login"));
                    return TestResponse.success("Authenticated as " + login);
                }
                case GITLAB -> {
                    String base = resolveBase(c, "https://gitlab.com");
                    Map<?, ?> body = restClient.get()
                            .uri(base + "/api/v4/user")
                            .header("PRIVATE-TOKEN", token)
                            .retrieve()
                            .body(Map.class);
                    String login = body == null ? "unknown" : String.valueOf(body.get("username"));
                    return TestResponse.success("Authenticated as " + login);
                }
                case JIRA -> {
                    String base = resolveBase(c, "https://your-domain.atlassian.net");
                    // Jira Cloud uses Basic auth with email + token. We treat the
                    // "accessToken" field as the API token; the configured
                    // externalAccount (or username) acts as the email when
                    // present, otherwise we send an anonymous probe.
                    String auth = c.getExternalAccount() == null
                            ? "Bearer " + token
                            : "Basic " + java.util.Base64.getEncoder().encodeToString(
                                    (c.getExternalAccount() + ":" + token).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    JsonNode body = restClient.get()
                            .uri(base + "/rest/api/3/myself")
                            .header(HttpHeaders.AUTHORIZATION, auth)
                            .header(HttpHeaders.ACCEPT, "application/json")
                            .retrieve()
                            .body(JsonNode.class);
                    String name = body == null ? "unknown" : body.path("displayName").asText("unknown");
                    return TestResponse.success("Connected as " + name);
                }
                default -> {
                    return TestResponse.failure("Provider has no health check yet");
                }
            }
        } catch (RestClientResponseException ex) {
            return TestResponse.failure("HTTP " + ex.getStatusCode() + ": "
                    + (ex.getResponseBodyAsString() == null ? "" : truncate(ex.getResponseBodyAsString(), 200)));
        } catch (Exception ex) {
            return TestResponse.failure(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private static String resolveBase(IntegrationConnection c, String fallback) {
        String b = c.getBaseUrl();
        if (b == null || b.isBlank()) return fallback;
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Used by webhook handlers to record an inbound delivery. */
    @Transactional
    public IntegrationEvent recordEvent(IntegrationProvider provider, UUID connectionId,
                                        String eventType, String deliveryId, String action,
                                        String payloadSummary) {
        return eventRepository.findByProviderAndDeliveryId(provider, deliveryId)
                .orElseGet(() -> eventRepository.save(IntegrationEvent.create(
                        provider, connectionId, eventType, deliveryId, action, payloadSummary)));
    }

    /** Used by webhook handlers to update status after processing. */
    @Transactional
    public IntegrationEvent finishEvent(IntegrationEvent event, boolean ok, String error) {
        if (ok) {
            event.markProcessed();
        } else {
            event.markFailed(error);
        }
        return eventRepository.save(event);
    }

    /** Used by webhook handlers to mark a delivery as ignored. */
    @Transactional
    public IntegrationEvent ignoreEvent(IntegrationEvent event, String reason) {
        event.markIgnored(reason);
        return eventRepository.save(event);
    }

    /**
     * Attach a connection to an existing event row. Used by the
     * dispatcher once it has matched an inbound webhook signature to a
     * stored connection; the connection ID cannot be known inside the
     * per-provider handler.
     */
    @Transactional
    public void attachConnection(java.util.UUID eventId, java.util.UUID connectionId) {
        eventRepository.findById(eventId).ifPresent(e -> {
            e.setConnectionId(connectionId);
            eventRepository.save(e);
        });
    }

    @Transactional
    public void touchLastUsed(UUID connectionId) {
        connectionRepository.findById(connectionId).ifPresent(c -> {
            c.setLastUsedAt(OffsetDateTime.now());
            connectionRepository.save(c);
        });
    }

    /**
     * Returns a decrypted token. Throws if the connection has none stored
     * (the caller can decide whether to treat that as an error or as a
     * degraded response).
     */
    @Transactional(readOnly = true)
    public String requireAccessToken(UUID connectionId) {
        IntegrationConnection c = requireConnection(connectionId);
        if (c.getStatus() != IntegrationStatus.ACTIVE) {
            throw new BadRequestException("Integration is not active");
        }
        String t = crypto.decrypt(c.getAccessTokenEncrypted());
        if (t == null || t.isBlank()) {
            throw new BadRequestException("No access token stored for this connection");
        }
        return t;
    }

    @Transactional(readOnly = true)
    public String requireWebhookSecret(UUID connectionId) {
        IntegrationConnection c = requireConnection(connectionId);
        String s = crypto.decrypt(c.getWebhookSecretEncrypted());
        return s == null ? "" : s;
    }

    /**
     * Helper to serialize a payload summary to JSON. Returns null if the
     * value cannot be serialized (so the row stores NULL rather than
     * failing the whole webhook delivery).
     */
    public String toJsonString(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.debug("payload summary serialization failed", e);
            return null;
        }
    }
}