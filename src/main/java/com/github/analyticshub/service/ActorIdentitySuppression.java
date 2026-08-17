package com.github.analyticshub.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 隐私工单完成后，仅保存 canonical actor 的不可逆摘要，阻止迟到的匿名 phase 再次绑定。
 */
final class ActorIdentitySuppression {

    private ActorIdentitySuppression() {
    }

    static String canonicalHash(UUID canonicalActorId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalActorId.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256 算法", exception);
        }
    }
}
