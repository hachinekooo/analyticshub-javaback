package com.github.analyticshub.util;

import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 加密工具类
 * 使用JDK 25增强的加密API
 * 提供API Key生成、HMAC签名验证等功能
 */
public final class CryptoUtils {

    private static final System.Logger log = System.getLogger(CryptoUtils.class.getName());

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String API_KEY_PREFIX = "ak_";
    private static final String SECRET_KEY_PREFIX = "sk_";

    private CryptoUtils() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 生成API Key
     * 格式: ak_<32字符随机字符串>
     */
    public static String generateApiKey() {
        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return API_KEY_PREFIX + encoded;
    }

    /**
     * 生成Secret Key
     * 格式: sk_<32字符随机字符串>
     */
    public static String generateSecretKey() {
        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return SECRET_KEY_PREFIX + encoded;
    }

    /**
     * 生成事件ID
     * 格式: evt_<timestamp>_<random>
     */
    public static String generateEventId() {
        long timestamp = System.currentTimeMillis();
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "evt_" + timestamp + "_" + randomPart;
    }

    public static String generateTrafficMetricId() {
        long timestamp = System.currentTimeMillis();
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return "tm_" + timestamp + "_" + randomPart;
    }

    /**
     * 验证UUID格式
     */
    public static boolean isValidUUID(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(uuid);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static String sha256Hex(String data) {
        if (data == null) {
            data = "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 unavailable", e);
        }
    }

    /**
     * 生成HMAC-SHA256签名
     * 使用JDK 25的增强加密API
     *
     * @param data      要签名的数据
     * @param secretKey 密钥
     * @return 十六进制签名字符串
     */
    public static String generateSignature(String data, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Hex.encodeHexString(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.log(System.Logger.Level.ERROR, "Failed to generate HMAC signature", e);
            throw new RuntimeException("Signature generation failed", e);
        }
    }

    /**
     * 验证HMAC-SHA256签名
     *
     * @param data      原始数据
     * @param signature 待验证的签名
     * @param secretKey 密钥
     * @return 签名是否有效
     */
    public static boolean verifySignature(String data, String signature, String secretKey) {
        String expectedSignature = generateSignature(data, secretKey);
        return constantTimeEquals(expectedSignature, signature);
    }

    /**
     * 常量时间字符串比较（防止时序攻击）
     * 使用JDK 25的改进
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int max = Math.max(x.length, y.length);

        int result = x.length ^ y.length;
        for (int i = 0; i < max; i++) {
            byte xb = i < x.length ? x[i] : 0;
            byte yb = i < y.length ? y[i] : 0;
            result |= xb ^ yb;
        }
        return result == 0;
    }

    /**
     * 简单的加密方法（用于存储数据库密码）
     * 注意：这是一个简化的实现，生产环境应使用更强的加密
     */
    public static String encrypt(String data) {
        if (data == null || data.isBlank()) {
            return null;
        }
        // 简单的Base64编码（实际应使用AES等加密算法）
        return Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 简单的解密方法
     */
    public static String decrypt(String encryptedData) {
        if (encryptedData == null || encryptedData.isBlank()) {
            return null;
        }
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedData);
            return new String(decodedBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.log(System.Logger.Level.WARNING, "Failed to decrypt data", e);
            return null;
        }
    }

    /**
     * 构建签名数据字符串
     * 格式: method|path|timestamp|deviceId|userId|body
     */
    public static String buildSignatureData(String method, String path, String timestamp,
                                           String deviceId, String userId, String body) {
        // Keep this format in sync with client-side signing to avoid mismatches.
        return String.join("|",
                method != null ? method : "",
                path != null ? path : "",
                timestamp != null ? timestamp : "",
                deviceId != null ? deviceId : "",
                userId != null ? userId : "",
                body != null ? body : ""
        );
    }
}
