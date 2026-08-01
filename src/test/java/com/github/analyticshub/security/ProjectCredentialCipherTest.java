package com.github.analyticshub.security;

import com.github.analyticshub.config.ProjectCredentialEncryptionProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectCredentialCipherTest {

    @Test
    void encryptsWithRandomNonceAndAuthenticatesProjectId() {
        ProjectCredentialCipher cipher = cipher(key(1), null);

        String first = cipher.encrypt("project_one", "database-password");
        String second = cipher.encrypt("project_one", "database-password");

        assertThat(first).startsWith("enc:v1:").isNotEqualTo(second);
        assertThat(cipher.decrypt("project_one", first)).isEqualTo("database-password");
        assertThatThrownBy(() -> cipher.decrypt("project_two", first))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTamperedCiphertext() {
        ProjectCredentialCipher cipher = cipher(key(2), null);
        String encrypted = cipher.encrypt("project_one", "database-password");
        char replacement = encrypted.endsWith("A") ? 'B' : 'A';
        String tampered = encrypted.substring(0, encrypted.length() - 1) + replacement;

        assertThatThrownBy(() -> cipher.decrypt("project_one", tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void readsLegacyBase64ButOnlyWritesAuthenticatedEnvelope() {
        ProjectCredentialCipher cipher = cipher(key(3), null);
        String legacy = Base64.getEncoder().encodeToString(
                "legacy-password".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(cipher.decrypt("project_one", legacy)).isEqualTo("legacy-password");
        assertThat(cipher.needsRotation(legacy)).isTrue();
        assertThat(cipher.encrypt("project_one", "legacy-password")).startsWith("enc:v1:");
    }

    @Test
    void previousKeySupportsOneStepRotation() {
        String oldKey = key(4);
        String newKey = key(5);
        ProjectCredentialCipher oldCipher = cipher(oldKey, null);
        String oldEnvelope = oldCipher.encrypt("project_one", "database-password");
        ProjectCredentialCipher rotatingCipher = cipher(newKey, oldKey);

        assertThat(rotatingCipher.decrypt("project_one", oldEnvelope)).isEqualTo("database-password");
        assertThat(rotatingCipher.needsRotation(oldEnvelope)).isTrue();
        String rotated = rotatingCipher.encrypt("project_one", "database-password");
        assertThat(rotatingCipher.needsRotation(rotated)).isFalse();
    }

    @Test
    void missingOrMalformedCurrentKeyFailsClosedForNewWrites() {
        ProjectCredentialCipher missing = cipher(null, null);
        assertThat(missing.isConfigured()).isFalse();
        assertThatThrownBy(() -> missing.encrypt("project_one", "database-password"))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16]), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    private static ProjectCredentialCipher cipher(String current, String previous) {
        ProjectCredentialEncryptionProperties properties = new ProjectCredentialEncryptionProperties();
        properties.setEncryptionKey(current);
        properties.setPreviousEncryptionKey(previous);
        return new ProjectCredentialCipher(properties);
    }

    private static String key(int seed) {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (seed + index);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
