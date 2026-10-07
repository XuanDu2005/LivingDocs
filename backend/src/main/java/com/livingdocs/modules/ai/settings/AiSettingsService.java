package com.livingdocs.modules.ai.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.livingdocs.common.crypto.AesGcmCipher;
import com.livingdocs.common.exception.BadRequestException;
import com.livingdocs.modules.workspace.repository.WorkspaceSettingsRepository;
import com.livingdocs.modules.workspace.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Per-workspace AI configuration storage. Settings live inside the
 * {@code workspace_settings.extra} JSONB column so this feature does
 * not require a database migration.
 *
 * <p>API keys are encrypted with AES-GCM before persistence and are
 * never returned to the client (only a masked preview is echoed back).
 *
 * <p>Reads and writes go through {@link JdbcTemplate} (not the JPA
 * entity) so the entity manager does not try to re-flush the row with
 * a {@code varchar} bound to a {@code jsonb} column, which PostgreSQL
 * refuses.
 */
@Service
public class AiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);
    private static final String EXTRA_KEY = "ai";

    private final WorkspaceSettingsRepository settingsRepository;
    private final WorkspaceService workspaceService;
    private final AesGcmCipher cipher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public AiSettingsService(WorkspaceSettingsRepository settingsRepository,
                             WorkspaceService workspaceService,
                             AesGcmCipher cipher,
                             ObjectMapper objectMapper,
                             JdbcTemplate jdbc) {
        this.settingsRepository = settingsRepository;
        this.workspaceService = workspaceService;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Transactional
    public AiSettings get(UUID actorId, UUID workspaceId) {
        workspaceService.requireMember(actorId, workspaceId);
        return loadOrDefault(workspaceId);
    }

    @Transactional
    public AiSettings update(UUID actorId, UUID workspaceId, UpdateAiSettingsRequest req) {
        workspaceService.requireRole(actorId, workspaceId,
                com.livingdocs.modules.workspace.model.WorkspaceRole.MANAGER);

        // Read existing extra (raw jsonb) outside the entity.
        JsonNode root = readExtraRaw(workspaceId);
        AiSettings current = fromJson(root.path(EXTRA_KEY));
        AiProvider provider = req.provider() != null ? req.provider() : current.provider();
        String model = req.model() != null ? req.model() : current.model();
        String baseUrl = req.baseUrl() != null ? req.baseUrl() : current.baseUrl();
        Double temperature = req.temperature() != null ? req.temperature() : current.temperature();
        Integer maxTokens = req.maxTokens() != null ? req.maxTokens() : current.maxTokens();

        boolean hasKey = current.hasApiKey();
        String storedCipher = readString(root.path(EXTRA_KEY), "api_key_cipher");

        if (req.wantsClear()) {
            storedCipher = null;
            hasKey = false;
        } else if (req.hasApiKey()) {
            storedCipher = cipher.encrypt(req.apiKey());
            hasKey = true;
        }

        AiSettings updated = new AiSettings(
                provider, model, baseUrl, temperature, maxTokens, hasKey,
                hasKey ? mask(storedCipher) : null);

        JsonNode aiNode = toJson(updated, storedCipher);
        com.fasterxml.jackson.databind.node.ObjectNode obj;
        if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode existing) {
            obj = existing;
        } else {
            obj = objectMapper.createObjectNode();
        }
        obj.set(EXTRA_KEY, aiNode);

        try {
            String newExtra = objectMapper.writeValueAsString(obj);
            // Ensure a row exists (no-op if it does) then update via
            // native SQL with an explicit jsonb cast.
            jdbc.update("INSERT INTO workspace_settings (workspace_id, extra) " +
                            "VALUES (?, CAST(? AS jsonb)) ON CONFLICT (workspace_id) DO NOTHING",
                    workspaceId, newExtra);
            jdbc.update("UPDATE workspace_settings SET extra = CAST(? AS jsonb), " +
                            "updated_at = NOW() WHERE workspace_id = ?",
                    newExtra, workspaceId);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Failed to serialize AI settings");
        }

        return updated;
    }

    @Transactional(readOnly = true)
    public String resolveApiKey(UUID workspaceId) {
        AiSettings s = loadOrDefault(workspaceId);
        if (!s.hasApiKey()) return null;
        JsonNode root = readExtraRaw(workspaceId);
        String cipherText = readString(root.path(EXTRA_KEY), "api_key_cipher");
        if (cipherText == null || cipherText.isBlank()) return null;
        try {
            return cipher.decrypt(cipherText);
        } catch (Exception e) {
            log.warn("Failed to decrypt AI key for workspace {}", workspaceId, e);
            return null;
        }
    }

    /**
     * Internal lookup for the AI service client — skips the membership
     * check because the request is already authorised by the caller
     * (e.g. a controller that has verified the actor).
     */
    @Transactional(readOnly = true)
    public AiSettings resolveForAiClient(UUID workspaceId) {
        return loadOrDefault(workspaceId);
    }

    // ---- internal helpers ----

    private JsonNode readExtraRaw(UUID workspaceId) {
        try {
            String raw = settingsRepository.findExtraRaw(workspaceId);
            if (raw == null) return objectMapper.createObjectNode();
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.debug("workspace_settings.extra read failed for {}, using empty", workspaceId, e);
            return objectMapper.createObjectNode();
        }
    }

    private AiSettings loadOrDefault(UUID workspaceId) {
        JsonNode root = readExtraRaw(workspaceId);
        return fromJson(root.path(EXTRA_KEY));
    }

    private AiSettings fromJson(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull() || !n.isObject()) {
            return AiSettings.defaults();
        }
        AiProvider provider = parseProvider(n.path("provider").asText(null));
        String model = n.path("model").asText("gpt-4o-mini");
        String baseUrl = n.path("baseUrl").isMissingNode() || n.path("baseUrl").isNull()
                ? null : n.path("baseUrl").asText();
        Double temperature = n.path("temperature").isMissingNode() || n.path("temperature").isNull()
                ? 0.2 : n.path("temperature").asDouble();
        Integer maxTokens = n.path("maxTokens").isMissingNode() || n.path("maxTokens").isNull()
                ? 2048 : n.path("maxTokens").asInt();
        String cipherText = readString(n, "api_key_cipher");
        boolean hasKey = cipherText != null && !cipherText.isBlank();
        return new AiSettings(provider, model, baseUrl, temperature, maxTokens, hasKey,
                hasKey ? mask(cipherText) : null);
    }

    private JsonNode toJson(AiSettings s, String storedCipher) {
        com.fasterxml.jackson.databind.node.ObjectNode obj = objectMapper.createObjectNode();
        obj.put("provider", s.provider().name());
        obj.put("model", s.model());
        if (s.baseUrl() != null) obj.put("baseUrl", s.baseUrl());
        if (s.temperature() != null) obj.put("temperature", s.temperature());
        if (s.maxTokens() != null) obj.put("maxTokens", s.maxTokens());
        if (storedCipher != null) obj.put("api_key_cipher", storedCipher);
        return obj;
    }

    private AiProvider parseProvider(String raw) {
        if (raw == null || raw.isBlank()) return AiProvider.SANDBOX;
        try { return AiProvider.valueOf(raw.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return AiProvider.SANDBOX; }
    }

    private String readString(JsonNode parent, String field) {
        JsonNode v = parent.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private String mask(String cipherText) {
        if (cipherText == null || cipherText.length() < 8) return "••••";
        return "••••" + cipherText.substring(cipherText.length() - 4);
    }
}
