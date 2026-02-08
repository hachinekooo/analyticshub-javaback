package com.github.analyticshub.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 加密工具类测试
 */
class CryptoUtilsTest {

    @Test
    void testGenerateApiKey() {
        String apiKey = CryptoUtils.generateApiKey();
        assertNotNull(apiKey);
        assertTrue(apiKey.startsWith("ak_"));
        assertTrue(apiKey.length() > 10);
    }

    @Test
    void testGenerateSecretKey() {
        String secretKey = CryptoUtils.generateSecretKey();
        assertNotNull(secretKey);
        assertTrue(secretKey.startsWith("sk_"));
        assertTrue(secretKey.length() > 10);
    }

    @Test
    void testGenerateEventId() {
        String eventId = CryptoUtils.generateEventId();
        assertNotNull(eventId);
        assertTrue(eventId.startsWith("evt_"));
    }

    @Test
    void testIsValidUUID() {
        assertTrue(CryptoUtils.isValidUUID("550e8400-e29b-41d4-a716-446655440000"));
        assertFalse(CryptoUtils.isValidUUID("invalid-uuid"));
        assertFalse(CryptoUtils.isValidUUID(null));
        assertFalse(CryptoUtils.isValidUUID(""));
    }

    @Test
    void testSignatureGeneration() {
        String data = "test data";
        String secretKey = "test-secret-key";
        
        String signature = CryptoUtils.generateSignature(data, secretKey);
        assertNotNull(signature);
        assertTrue(signature.length() > 0);
        
        // 验证签名
        assertTrue(CryptoUtils.verifySignature(data, signature, secretKey));
        assertFalse(CryptoUtils.verifySignature(data, "invalid-signature", secretKey));
    }

    @Test
    void testEncryptDecrypt() {
        String original = "test-password";
        
        String encrypted = CryptoUtils.encrypt(original);
        assertNotNull(encrypted);
        assertNotEquals(original, encrypted);
        
        String decrypted = CryptoUtils.decrypt(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void testBuildSignatureData() {
        String signatureData = CryptoUtils.buildSignatureData(
                "POST",
                "/api/v1/events",
                "1234567890",
                "device-123",
                "user-123",
                "{\"test\":\"data\"}"
        );
        
        assertNotNull(signatureData);
        assertTrue(signatureData.contains("POST"));
        assertTrue(signatureData.contains("/api/v1/events"));
    }
}
