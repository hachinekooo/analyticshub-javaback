package com.github.analyticshub.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的限流服务
 * 用于防止暴力破解 Admin Token
 */
@Service
public class RateLimitService {

    // IP -> 失败记录
    private final Map<String, FailureRecord> failureRecords = new ConcurrentHashMap<>();

    // 配置
    private static final int MAX_FAILURES = 5;           // 最大失败次数
    private static final long BAN_DURATION_MS = 15 * 60 * 1000;  // 封禁时长 15 分钟
    private static final long CLEANUP_INTERVAL_MS = 60 * 1000;   // 清理间隔 1 分钟
    
    private long lastCleanupTime = System.currentTimeMillis();

    /**
     * 记录失败尝试
     */
    public void recordFailure(String ip) {
        cleanupIfNeeded();
        
        failureRecords.compute(ip, (k, record) -> {
            if (record == null) {
                return new FailureRecord(1, Instant.now().toEpochMilli());
            }
            
            // 如果已经被封禁，延长封禁时间
            if (record.isBanned()) {
                return new FailureRecord(record.failureCount + 1, Instant.now().toEpochMilli());
            }
            
            return new FailureRecord(record.failureCount + 1, record.firstFailureTime);
        });
    }

    /**
     * 检查 IP 是否被封禁
     */
    public boolean isBanned(String ip) {
        cleanupIfNeeded();
        
        FailureRecord record = failureRecords.get(ip);
        if (record == null) {
            return false;
        }
        
        return record.isBanned();
    }

    /**
     * 获取剩余封禁时间（秒）
     */
    public long getRemainingBanTimeSeconds(String ip) {
        FailureRecord record = failureRecords.get(ip);
        if (record == null || !record.isBanned()) {
            return 0;
        }
        
        long elapsedMs = System.currentTimeMillis() - record.firstFailureTime;
        long remainingMs = BAN_DURATION_MS - elapsedMs;
        return Math.max(0, remainingMs / 1000);
    }

    /**
     * 重置 IP 的失败记录（认证成功时调用）
     */
    public void resetFailures(String ip) {
        failureRecords.remove(ip);
    }

    /**
     * 获取当前失败次数
     */
    public int getFailureCount(String ip) {
        FailureRecord record = failureRecords.get(ip);
        return record == null ? 0 : record.failureCount;
    }

    /**
     * 定期清理过期记录
     */
    private void cleanupIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupTime < CLEANUP_INTERVAL_MS) {
            return;
        }
        
        lastCleanupTime = now;
        failureRecords.entrySet().removeIf(entry -> {
            FailureRecord record = entry.getValue();
            long elapsedMs = now - record.firstFailureTime;
            return elapsedMs > BAN_DURATION_MS;
        });
    }

    /**
     * 失败记录
     */
    private static class FailureRecord {
        final int failureCount;
        final long firstFailureTime;

        FailureRecord(int failureCount, long firstFailureTime) {
            this.failureCount = failureCount;
            this.firstFailureTime = firstFailureTime;
        }

        boolean isBanned() {
            if (failureCount < MAX_FAILURES) {
                return false;
            }
            
            long elapsedMs = System.currentTimeMillis() - firstFailureTime;
            return elapsedMs < BAN_DURATION_MS;
        }
    }
}
