package com.livingdocs.modules.integrations.model;

import com.livingdocs.common.persistence.UuidGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A connection between LivingDocs and an external service
 * (GitHub / GitLab / Jira / Slack).
 *
 * <p>Credentials are stored AES-GCM encrypted by the platform
 * {@code AesGcmCipher} bean; the column names end in {@code _encrypted}
 * so the convention is enforced everywhere new tokens are added.
 *
 * <p>Connections are scoped either to a workspace (repository / Jira /
 * per-project channel) or to the platform as a whole (organisation-wide
 * Slack workspace). The {@code workspace_id} column distinguishes the
 * two cases.
 */
@Entity
@Table(name = "integration_connections")
public class IntegrationConnection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 16)
    private IntegrationProvider provider;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "external_account", nullable = false, length = 255)
    private String externalAccount;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "scopes", columnDefinition = "text")
    private String scopes;

    @Column(name = "access_token_encrypted", columnDefinition = "text")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted", columnDefinition = "text")
    private String refreshTokenEncrypted;

    @Column(name = "webhook_secret_encrypted", columnDefinition = "text")
    private String webhookSecretEncrypted;

    @Column(name = "webhook_external_id", length = 128)
    private String webhookExternalId;

    @Column(name = "default_channel", length = 120)
    private String defaultChannel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IntegrationStatus status = IntegrationStatus.ACTIVE;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private OffsetDateTime connectedAt;

    public IntegrationConnection() {
        // JPA
    }

    public static IntegrationConnection create(IntegrationProvider provider,
                                                 UUID workspaceId,
                                                 String displayName,
                                                 String externalAccount) {
        IntegrationConnection c = new IntegrationConnection();
        c.id = UuidGenerator.newId();
        c.provider = provider;
        c.workspaceId = workspaceId;
        c.displayName = displayName;
        c.externalAccount = externalAccount;
        c.status = IntegrationStatus.ACTIVE;
        c.connectedAt = OffsetDateTime.now();
        return c;
    }

    public UUID getId() { return id; }
    public IntegrationProvider getProvider() { return provider; }
    public void setProvider(IntegrationProvider v) { this.provider = v; }
    public UUID getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(UUID v) { this.workspaceId = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getExternalAccount() { return externalAccount; }
    public void setExternalAccount(String v) { this.externalAccount = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getScopes() { return scopes; }
    public void setScopes(String v) { this.scopes = v; }
    public String getAccessTokenEncrypted() { return accessTokenEncrypted; }
    public void setAccessTokenEncrypted(String v) { this.accessTokenEncrypted = v; }
    public String getRefreshTokenEncrypted() { return refreshTokenEncrypted; }
    public void setRefreshTokenEncrypted(String v) { this.refreshTokenEncrypted = v; }
    public String getWebhookSecretEncrypted() { return webhookSecretEncrypted; }
    public void setWebhookSecretEncrypted(String v) { this.webhookSecretEncrypted = v; }
    public String getWebhookExternalId() { return webhookExternalId; }
    public void setWebhookExternalId(String v) { this.webhookExternalId = v; }
    public String getDefaultChannel() { return defaultChannel; }
    public void setDefaultChannel(String v) { this.defaultChannel = v; }
    public IntegrationStatus getStatus() { return status; }
    public void setStatus(IntegrationStatus v) { this.status = v; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(OffsetDateTime v) { this.lastUsedAt = v; }
    public OffsetDateTime getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(OffsetDateTime v) { this.lastSyncedAt = v; }
    public OffsetDateTime getConnectedAt() { return connectedAt; }
    public void setConnectedAt(OffsetDateTime v) { this.connectedAt = v; }
}