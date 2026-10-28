package com.livingdocs.common.crypto;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-shot migration that encrypts OAuth tokens that are still stored in
 * plaintext.
 *
 * <h2>Why this is not a Flyway migration</h2>
 * Flyway runs before the Spring context is fully initialised, so the
 * {@link AesGcmCipher} bean is not available yet. Worse, PostgreSQL's
 * {@code pgcrypto} does not expose raw AES-GCM — {@code pgp_sym_encrypt}
 * produces an OpenPGP container that the Java cipher cannot read.
 * Re-implementing AES-GCM in PL/pgSQL is neither possible nor
 * desirable.
 *
 * <p>Doing it here means the encryption uses exactly the same code path
 * as a normal write, so there is no risk of the two disagreeing.
 *
 * <h2>Why native SQL instead of loading the entities</h2>
 * The obvious implementation — load the entity, assign the same token
 * back, save it — silently does nothing. Hibernate's dirty checking
 * compares the loaded value with the current one; they are the same
 * {@code String} instance, so no UPDATE is issued and the row keeps its
 * plaintext forever. Writing the ciphertext with an explicit UPDATE
 * sidesteps that entirely, and reading the raw column with a native
 * query means the {@link EncryptedStringConverter} does not hand us
 * already-decrypted values (which would be re-encrypted on every boot).
 *
 * <h2>Why it is safe to run on every boot</h2>
 * Rows that are already encrypted are detected by their
 * {@code enc:v1:} prefix and skipped, so running this repeatedly is a
 * no-op after the first pass.
 */
@Component
public class GithubTokenEncryptionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GithubTokenEncryptionRunner.class);

    /** Marker prefix of our ciphertext format. */
    private static final String ENCRYPTED_PREFIX = "enc:v1:";

    @PersistenceContext
    private EntityManager entityManager;

    private final AesGcmCipher cipher;

    public GithubTokenEncryptionRunner(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int migrated = encryptGithubTokens() + encryptOAuthTokens();
        if (migrated > 0) {
            log.info("Encrypted {} plaintext OAuth access token(s) at rest", migrated);
        }
    }

    private int encryptGithubTokens() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT id, access_token FROM github_connections")
                .getResultList();

        int migrated = 0;
        for (Object[] row : rows) {
            Object id = row[0];
            Object token = row[1];
            if (!(token instanceof String raw) || isEncrypted(raw)) {
                continue;
            }
            entityManager.createNativeQuery(
                            "UPDATE github_connections SET access_token = :token WHERE id = :id")
                    .setParameter("token", ENCRYPTED_PREFIX + cipher.encrypt(raw))
                    .setParameter("id", id)
                    .executeUpdate();
            migrated++;
        }
        return migrated;
    }

    private int encryptOAuthTokens() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT id, access_token, refresh_token FROM user_oauth_identities")
                .getResultList();

        int migrated = 0;
        for (Object[] row : rows) {
            Object id = row[0];
            Object access = row[1];
            Object refresh = row[2];

            String accessCipher = toCipher(access);
            String refreshCipher = toCipher(refresh);
            if (accessCipher == null && refreshCipher == null) {
                continue;
            }

            if (accessCipher != null) {
                entityManager.createNativeQuery(
                                "UPDATE user_oauth_identities SET access_token = :token WHERE id = :id")
                        .setParameter("token", accessCipher)
                        .setParameter("id", id)
                        .executeUpdate();
            }
            if (refreshCipher != null) {
                entityManager.createNativeQuery(
                                "UPDATE user_oauth_identities SET refresh_token = :token WHERE id = :id")
                        .setParameter("token", refreshCipher)
                        .setParameter("id", id)
                        .executeUpdate();
            }
            migrated++;
        }
        return migrated;
    }

    /** Returns the ciphertext for a plaintext value, or {@code null} to skip. */
    private String toCipher(Object value) {
        if (!(value instanceof String raw) || raw.isBlank() || isEncrypted(raw)) {
            return null;
        }
        return ENCRYPTED_PREFIX + cipher.encrypt(raw);
    }

    private static boolean isEncrypted(String value) {
        return value.startsWith(ENCRYPTED_PREFIX);
    }
}
