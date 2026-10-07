package com.livingdocs.common.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM authenticated encryption used to protect workspace-scoped secrets
 * (AI provider API keys) at rest. A 256-bit key is derived from the
 * application secret so the same value across restarts is deterministic
 * while keeping the key out of source control.
 *
 * <p>Ciphertext layout (Base64-encoded): {@code [12-byte IV][ciphertext+tag]}.
 */
@Component
public class AesGcmCipher {

    private static final Logger log = LoggerFactory.getLogger(AesGcmCipher.class);
    private static final String ALGO = "AES";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private final SecureRandom random = new SecureRandom();
    private final String appSecret;

    private SecretKey key;

    public AesGcmCipher(@Value("${app.security.jwt.secret}") String appSecret) {
        this.appSecret = appSecret;
    }

    @PostConstruct
    void init() {
        try {
            byte[] seed = appSecret.getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed);
            this.key = new SecretKeySpec(digest, ALGO);
        } catch (Exception e) {
            log.error("Failed to derive AES key from app secret", e);
            throw new IllegalStateException("AES init failed", e);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        if (plaintext.isEmpty()) return "";
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("AES encrypt failed", e);
        }
    }

    public String decrypt(String token) {
        if (token == null) return null;
        if (token.isEmpty()) return "";
        try {
            byte[] data = Base64.getDecoder().decode(token);
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(data, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[data.length - IV_LEN];
            System.arraycopy(data, IV_LEN, ct, 0, ct.length);
            Cipher c = Cipher.getInstance(TRANSFORM);
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = c.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Likely a key mismatch after a secret rotation — surface a clear error
            // so callers know the stored value is no longer recoverable.
            throw new IllegalStateException("AES decrypt failed (key rotated?)", e);
        }
    }
}
