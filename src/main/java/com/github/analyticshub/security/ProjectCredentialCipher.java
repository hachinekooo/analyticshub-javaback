package com.github.analyticshub.security;

import com.github.analyticshub.config.ProjectCredentialEncryptionProperties;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Authenticated encryption for project database passwords.
 *
 * <p>The project id is authenticated as AAD, so ciphertext copied to another
 * project row cannot be decrypted there. Values without an {@code enc:}
 * envelope are treated only as the legacy 1.0.0 Base64 format and are never
 * produced by this class.</p>
 */
@Component
public class ProjectCredentialCipher {

    private static final String ENVELOPE_PREFIX = "enc:v1:";
    private static final String AAD_PREFIX = "analyticshub:project-db-password:v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final KeyMaterial currentKey;
    private final KeyMaterial previousKey;

    public ProjectCredentialCipher(ProjectCredentialEncryptionProperties properties) {
        this.currentKey = parseOptionalKey(properties.getEncryptionKey(), "current");
        this.previousKey = parseOptionalKey(properties.getPreviousEncryptionKey(), "previous");
    }

    public boolean isConfigured() {
        return currentKey != null;
    }

    public String encrypt(String projectId, String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        requireProjectId(projectId);
        if (currentKey == null) {
            throw new IllegalStateException("Project credential encryption key is not configured");
        }

        byte[] nonce = new byte[NONCE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, currentKey.secretKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(projectId));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ENVELOPE_PREFIX
                    + currentKey.keyId() + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce) + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt project credential", exception);
        }
    }

    public String decrypt(String projectId, String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return null;
        }
        requireProjectId(projectId);
        if (!storedValue.startsWith("enc:")) {
            return decryptLegacy(storedValue);
        }
        if (!storedValue.startsWith(ENVELOPE_PREFIX)) {
            throw new IllegalStateException("Unsupported project credential envelope");
        }

        String[] parts = storedValue.split(":", 5);
        if (parts.length != 5) {
            throw new IllegalStateException("Invalid project credential envelope");
        }
        KeyMaterial key = resolveKey(parts[2]);
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[3]);
            byte[] ciphertext = Base64.getUrlDecoder().decode(parts[4]);
            if (nonce.length != NONCE_BYTES || ciphertext.length <= GCM_TAG_BITS / Byte.SIZE) {
                throw new IllegalStateException("Invalid project credential envelope");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key.secretKey(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(projectId));
            return decodeUtf8(cipher.doFinal(ciphertext));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt project credential", exception);
        }
    }

    public boolean needsRotation(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return false;
        }
        if (currentKey == null || !storedValue.startsWith(ENVELOPE_PREFIX)) {
            return true;
        }
        String[] parts = storedValue.split(":", 5);
        return parts.length != 5 || !currentKey.keyId().equals(parts[2]);
    }

    private KeyMaterial resolveKey(String keyId) {
        if (currentKey != null && currentKey.keyId().equals(keyId)) {
            return currentKey;
        }
        if (previousKey != null && previousKey.keyId().equals(keyId)) {
            return previousKey;
        }
        throw new IllegalStateException("Project credential encryption key is unavailable");
    }

    private static String decryptLegacy(String storedValue) {
        try {
            return decodeUtf8(Base64.getDecoder().decode(storedValue));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid legacy project credential", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("Project credential is not valid UTF-8", exception);
        }
    }

    private static KeyMaterial parseOptionalKey(String encoded, String label) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encoded.strip());
            if (keyBytes.length != KEY_BYTES) {
                throw new IllegalStateException(
                        "The " + label + " project credential encryption key must decode to 32 bytes"
                );
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String keyId = Hex.encodeHexString(digest.digest(keyBytes)).substring(0, 16);
            return new KeyMaterial(keyId, new SecretKeySpec(keyBytes, "AES"));
        } catch (IllegalArgumentException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "The " + label + " project credential encryption key must be Base64-encoded",
                    exception
            );
        }
    }

    private static byte[] aad(String projectId) {
        return (AAD_PREFIX + projectId).getBytes(StandardCharsets.UTF_8);
    }

    private static void requireProjectId(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required for credential encryption");
        }
    }

    private record KeyMaterial(String keyId, SecretKeySpec secretKey) {
    }
}
