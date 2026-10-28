package com.livingdocs.common.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the at-rest encryption of OAuth tokens.
 */
class EncryptedStringConverterTest {

    private static final String APP_SECRET =
            "change-me-please-this-is-a-development-only-secret-32b+";

    private AesGcmCipher cipher;
    private EncryptedStringConverter converter;

    @BeforeEach
    void setUp() {
        cipher = new AesGcmCipher(APP_SECRET);
        cipher.init();
        converter = new EncryptedStringConverter(cipher);
    }

    @Test
    void encryptThenDecryptReturnsTheOriginalValue() {
        String plaintext = "gho_16C7e42F292c6912E7710c838347Ae178B4a";

        String stored = converter.convertToDatabaseColumn(plaintext);
        assertEquals(plaintext, converter.convertToEntityAttribute(stored));
    }

    @Test
    void storedValueIsNotThePlaintext() {
        String plaintext = "gho_secret_token";

        String stored = converter.convertToDatabaseColumn(plaintext);

        assertNotEquals(plaintext, stored);
        assertFalse(stored.contains(plaintext));
        assertTrue(stored.startsWith("enc:v1:"));
    }

    @Test
    void encryptingTwiceDoesNotDoubleWrap() {
        String plaintext = "gho_secret_token";

        String once = converter.convertToDatabaseColumn(plaintext);
        String twice = converter.convertToDatabaseColumn(once);

        assertEquals(once, twice, "an already-encrypted value must not be re-encrypted");
    }

    @Test
    void everyEncryptionUsesAFreshIv() {
        String plaintext = "same token";

        String first = converter.convertToDatabaseColumn(plaintext);
        String second = converter.convertToDatabaseColumn(plaintext);

        assertNotEquals(first, second,
                "a fresh IV per encryption means identical tokens differ at rest");
        assertEquals(plaintext, converter.convertToEntityAttribute(first));
        assertEquals(plaintext, converter.convertToEntityAttribute(second));
    }

    @Test
    void legacyPlaintextRowsAreReadUnchanged() {
        // A row written before the converter existed holds a raw token.
        // It must still be readable so the migration can happen online.
        String legacy = "gho_legacy_plaintext";

        assertEquals(legacy, converter.convertToEntityAttribute(legacy));
    }

    @Test
    void nullAndEmptyValuesAreHandled() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
        assertEquals("", converter.convertToDatabaseColumn(""));
        assertEquals("", converter.convertToEntityAttribute(""));
    }

    @Test
    void ciphertextFromAnotherKeyYieldsNullInsteadOfCrashing() {
        // A rotated app secret makes the old ciphertext unreadable. The
        // converter must not throw: the user can re-connect their account.
        AesGcmCipher otherCipher = new AesGcmCipher("a-completely-different-secret-value-xyz");
        otherCipher.init();
        String stored = "enc:v1:" + otherCipher.encrypt("gho_token");

        assertNull(converter.convertToEntityAttribute(stored));
    }

    @Test
    void cipherRoundTripWorks() {
        String ciphertext = cipher.encrypt("hello");

        assertNotEquals("hello", ciphertext);
        assertEquals("hello", cipher.decrypt(ciphertext));
    }
}
