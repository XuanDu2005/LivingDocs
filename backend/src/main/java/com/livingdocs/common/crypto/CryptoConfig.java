package com.livingdocs.common.crypto;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the crypto helpers into the Spring context.
 *
 * <h2>Why the converter is registered as a bean</h2>
 * JPA instantiates an {@code @Converter} reflectively when Hibernate
 * cannot resolve it from the bean container, which means the converter
 * has no way to receive the {@link AesGcmCipher} through constructor
 * injection. Registering it as a bean lets Hibernate find it in the
 * Spring context and inject the cipher properly. The reflective
 * fallback still works — it resolves the cipher through
 * {@link AesGcmCipher#current()}.
 */
@Configuration
public class CryptoConfig {

    @Bean
    public EncryptedStringConverter encryptedStringConverter(AesGcmCipher cipher) {
        return new EncryptedStringConverter(cipher);
    }
}
