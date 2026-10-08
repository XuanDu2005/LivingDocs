package com.livingdocs.modules.integrations.service;

import com.livingdocs.common.crypto.AesGcmCipher;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the platform {@link AesGcmCipher} so the rest of
 * the integrations module does not depend on the {@code common.crypto}
 * package directly. Reusing the same cipher as the AI settings module
 * means a single secret rotation cycle covers both feature surfaces.
 */
@Component
public class IntegrationCrypto {

    private final AesGcmCipher cipher;

    public IntegrationCrypto(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    public String encrypt(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            return cipher.decrypt(ciphertext);
        } catch (Exception e) {
            // Likely an encryption key was rotated; the stored value is no
            // longer recoverable. Treat the connection as if the secret
            // was never set and let the caller decide what to do.
            return null;
        }
    }
}