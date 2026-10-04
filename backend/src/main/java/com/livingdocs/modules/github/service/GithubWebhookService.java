package com.livingdocs.modules.github.service;

import com.livingdocs.common.exception.UnauthorizedException;
import com.livingdocs.modules.github.config.GithubProperties;
import com.livingdocs.modules.github.model.WebhookEvent;
import com.livingdocs.modules.github.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Verifies and persists inbound GitHub webhooks.
 *
 * <p>GitHub signs every delivery with HMAC-SHA256 using the
 * configured shared secret. The signature is delivered in the
 * {@code X-Hub-Signature-256} header as {@code sha256=<hex>}. A
 * constant-time comparison guards against timing attacks.
 */
@Service
public class GithubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GithubWebhookService.class);
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String HMAC_ALG = "HmacSHA256";

    private final WebhookEventRepository webhookEventRepository;
    private final GithubProperties properties;
    private final GithubIngestionService ingestionService;

    public GithubWebhookService(WebhookEventRepository webhookEventRepository,
                                GithubProperties properties,
                                GithubIngestionService ingestionService) {
        this.webhookEventRepository = webhookEventRepository;
        this.properties = properties;
        this.ingestionService = ingestionService;
    }

    /**
     * Validates the signature, persists the event, and triggers
     * downstream processing. Returns the persisted event (or the
     * previously-persisted one if {@code deliveryId} is a duplicate).
     */
    @Transactional
    public WebhookEvent handle(String eventType, String deliveryId, String signature,
                               String action, UUID repositoryId, String payload) {
        if (eventType == null || eventType.isBlank()) {
            throw new UnauthorizedException("Missing X-GitHub-Event header");
        }
        if (deliveryId == null || deliveryId.isBlank()) {
            throw new UnauthorizedException("Missing X-GitHub-Delivery header");
        }
        verifySignature(payload, signature);

        // Idempotency: if the same delivery was already received, skip
        // re-processing but return the existing record.
        return webhookEventRepository.findByDeliveryId(deliveryId)
                .orElseGet(() -> persistAndProcess(eventType, deliveryId, action, repositoryId, payload));
    }

    private WebhookEvent persistAndProcess(String eventType, String deliveryId, String action,
                                           UUID repositoryId, String payload) {
        WebhookEvent event = WebhookEvent.create(eventType, deliveryId, action, payload, repositoryId);
        WebhookEvent saved = webhookEventRepository.save(event);
        try {
            ingestionService.process(saved);
            saved.markProcessed();
        } catch (RuntimeException ex) {
            log.warn("Webhook processing failed for delivery {}: {}", deliveryId, ex.getMessage());
            saved.markFailed(ex.getMessage());
        }
        return webhookEventRepository.save(saved);
    }

    private void verifySignature(String payload, String signature) {
        String secret = properties.getWebhook().getSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("Webhook secret not configured — skipping signature verification (dev mode only)");
            return;
        }
        if (signature == null || !signature.startsWith(SIGNATURE_PREFIX)) {
            throw new UnauthorizedException("Missing or malformed X-Hub-Signature-256 header");
        }
        String expected = computeHmac(payload, secret);
        String provided = signature.substring(SIGNATURE_PREFIX.length());
        if (!constantTimeEquals(expected, provided)) {
            throw new UnauthorizedException("Webhook signature mismatch");
        }
    }

    private static String computeHmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute webhook signature", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }
}