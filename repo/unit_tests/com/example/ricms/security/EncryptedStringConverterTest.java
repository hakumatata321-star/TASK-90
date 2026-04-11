package com.example.ricms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for EncryptedStringConverter (AES-256-GCM) covering:
 *  - encrypt/decrypt round-trip for various inputs
 *  - null handling
 *  - each encryption produces unique ciphertext (IV randomness)
 *  - invalid key length rejected at construction
 */
class EncryptedStringConverterTest {

    private EncryptedStringConverter converter;

    @BeforeEach
    void setUp() {
        // Generate a valid 32-byte key for testing
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) (i + 1);
        String base64Key = Base64.getEncoder().encodeToString(key);
        converter = new EncryptedStringConverter(base64Key);
    }

    // ── Round-trip correctness ────────────────────────────────────────────────

    @Test
    void roundTrip_simpleString_decryptsToOriginal() {
        String original = "Hello, World!";
        String encrypted = converter.convertToDatabaseColumn(original);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void roundTrip_phoneNumber_decryptsToOriginal() {
        String phone = "+1-555-123-4567";
        String encrypted = converter.convertToDatabaseColumn(phone);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertThat(decrypted).isEqualTo(phone);
    }

    @ParameterizedTest(name = "roundTrip: \"{0}\"")
    @ValueSource(strings = {
        "",
        " ",
        "a",
        "Unicode: \u00e9\u00e8\u00ea \u4e16\u754c",
        "Special chars: !@#$%^&*(){}[]|\\\"'<>?/",
        "A very long string that contains more than 256 characters to test that the encryption handles larger payloads correctly without truncation or corruption. This is important for fields that might store longer phone numbers or addresses from international formats."
    })
    void roundTrip_variousInputs_decryptsToOriginal(String original) {
        String encrypted = converter.convertToDatabaseColumn(original);
        String decrypted = converter.convertToEntityAttribute(encrypted);

        assertThat(decrypted).isEqualTo(original);
    }

    // ── Null handling ─────────────────────────────────────────────────────────

    @Test
    void encrypt_null_returnsNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void decrypt_null_returnsNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    // ── Ciphertext uniqueness (IV randomness) ─────────────────────────────────

    @Test
    void encrypt_samePlaintext_producesDifferentCiphertext() {
        String plaintext = "test-phone-number";
        String encrypted1 = converter.convertToDatabaseColumn(plaintext);
        String encrypted2 = converter.convertToDatabaseColumn(plaintext);

        // Different IVs must produce different ciphertext
        assertThat(encrypted1).isNotEqualTo(encrypted2);

        // Both must decrypt to the same plaintext
        assertThat(converter.convertToEntityAttribute(encrypted1)).isEqualTo(plaintext);
        assertThat(converter.convertToEntityAttribute(encrypted2)).isEqualTo(plaintext);
    }

    // ── Ciphertext is Base64 ──────────────────────────────────────────────────

    @Test
    void encrypt_outputIsValidBase64() {
        String encrypted = converter.convertToDatabaseColumn("test");

        assertThatCode(() -> Base64.getDecoder().decode(encrypted))
                .doesNotThrowAnyException();
    }

    // ── Invalid key length ────────────────────────────────────────────────────

    @Test
    void constructor_shortKey_throwsIllegalArgument() {
        byte[] shortKey = new byte[16]; // AES-128, not AES-256
        String base64Short = Base64.getEncoder().encodeToString(shortKey);

        assertThatThrownBy(() -> new EncryptedStringConverter(base64Short))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32-byte key");
    }

    // ── Tampered ciphertext ───────────────────────────────────────────────────

    @Test
    void decrypt_tamperedCiphertext_throwsException() {
        String encrypted = converter.convertToDatabaseColumn("sensitive data");
        // Tamper with the ciphertext
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0xFF; // flip last byte
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> converter.convertToEntityAttribute(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decrypt");
    }
}
