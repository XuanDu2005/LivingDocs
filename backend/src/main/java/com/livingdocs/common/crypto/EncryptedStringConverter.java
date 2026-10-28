package com.livingdocs.common.crypto;

import com.livingdocs.common.crypto.AesGcmCipher;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA converter that encrypts a secret column with AES-GCM.
 *
 * <h2>Why a converter and not a service-level encrypt</h2>
 * The GitHub OAuth token is read in a dozen places
 * ({@code GithubRepositoryService}, {@code CommitIngestionService},
 * the auto-update service, ...). Encrypting at the service boundary
 * would mean every one of those call sites has to remember to decrypt —
 * and a single miss silently sends a ciphertext to GitHub. Doing it in
 * the persistence layer means the entity always holds the plaintext and
 * the database always holds the ciphertext.
 *
 * <h2>Legacy plaintext</h2>
 * Rows written before this converter existed still hold a raw token.
 * {@code decrypt} detects that case (our ciphertext always starts with
 * the {@value #PREFIX} marker) and returns the value untouched, so the
 * migration can happen online without a data rewrite. Re-saving such a
 * row encrypts it.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);

    /**
     * Marker prefix for our ciphertext.
     *
     * <p>Base64 never contains {@code :}, so this can never collide
     * with a plaintext value that happens to be valid Base64.
     */
    static final String PREFIX = "enc:v1:";

    private final AesGcmCipher cipher;

    public EncryptedStringConverter(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    /**
     * No-arg constructor for JPA, which instantiates the converter
     * reflectively when it cannot obtain it from the Spring bean
     * container. In that case the cipher is fetched from the static
     * holder instead.
     */
    public EncryptedStringConverter() {
        this.cipher = null;
    }

    private AesGcmCipher cipher() {
        return cipher != null ? cipher : AesGcmCipher.current();
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        if (isEncrypted(attribute)) {
            // Already encrypted — encrypting twice would double-wrap it.
            return attribute;
        }
        return PREFIX + cipher().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return dbData;
        }
        if (!isEncrypted(dbData)) {
            // Legacy plaintext row — return it as-is so the app keeps
            // working before the row is re-saved.
            return dbData;
        }
        String payload = dbData.substring(PREFIX.length());
        try {
            return cipher().decrypt(payload);
        } catch (Exception e) {
            // A key rotation makes the old ciphertext unreadable. Log it
            // loudly and return null rather than crashing the whole
            // request — the user can re-connect GitHub.
            log.error("Failed to decrypt stored secret — the app secret was probably rotated", e);
            return null;
        }
    }

    private static boolean isEncrypted(String value) {
        return value.startsWith(PREFIX);
    }
}
