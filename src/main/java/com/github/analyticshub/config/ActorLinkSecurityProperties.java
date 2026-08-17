package com.github.analyticshub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务后端向 AnalyticsHub 提交 actor 绑定时使用的专用服务凭据。
 *
 * <p>凭据按调用方和项目同时收口，避免测试服务向生产项目写入身份关系。
 * 该凭据只允许调用 actor-link 内部接口，不能复用 Admin Token 或设备采集密钥。</p>
 */
@ConfigurationProperties(prefix = "app.security.actor-link")
public class ActorLinkSecurityProperties {

    static final long MAX_SIGNATURE_VALIDITY_MS = 900_000;

    private boolean enabled;
    private boolean requireLoopback = true;
    private long signatureValidityMs = 300_000;
    private int maxRequestBodyBytes = 16_384;
    private List<Client> clients = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequireLoopback() {
        return requireLoopback;
    }

    public void setRequireLoopback(boolean requireLoopback) {
        this.requireLoopback = requireLoopback;
    }

    public long getSignatureValidityMs() {
        return signatureValidityMs;
    }

    public void setSignatureValidityMs(long signatureValidityMs) {
        this.signatureValidityMs = signatureValidityMs;
    }

    public int getMaxRequestBodyBytes() {
        return maxRequestBodyBytes;
    }

    public void setMaxRequestBodyBytes(int maxRequestBodyBytes) {
        this.maxRequestBodyBytes = maxRequestBodyBytes;
    }

    public List<Client> getClients() {
        return clients;
    }

    public void setClients(List<Client> clients) {
        this.clients = clients == null ? new ArrayList<>() : new ArrayList<>(clients);
    }

    public Client requireClient(String serviceId, String projectId) {
        return clients.stream()
                .filter(client -> client.serviceId().equals(serviceId)
                        && client.projectId().equals(projectId))
                .findFirst()
                .orElse(null);
    }

    public void validate() {
        if (signatureValidityMs <= 0 || signatureValidityMs > MAX_SIGNATURE_VALIDITY_MS
                || maxRequestBodyBytes <= 0) {
            throw new IllegalStateException("Actor-link security limits must be positive and signature validity is capped");
        }
        if (!enabled) {
            return;
        }
        if (clients.isEmpty()) {
            throw new IllegalStateException("Actor-link security is enabled but no clients are configured");
        }
        for (Client client : clients) {
            if (client.serviceId().isBlank() || client.projectId().isBlank()
                    || client.secret().isBlank()
                    || !client.secret().equals(client.secret().strip())
                    || client.secret().length() < 32) {
                throw new IllegalStateException(
                        "Each actor-link client requires serviceId, projectId and an unpadded secret of at least 32 characters"
                );
            }
        }
        long uniqueServiceIds = clients.stream()
                .map(Client::serviceId)
                .distinct()
                .count();
        if (uniqueServiceIds != clients.size()) {
            throw new IllegalStateException("Each actor-link serviceId may authorize exactly one project");
        }
        long uniqueProjectIds = clients.stream()
                .map(Client::projectId)
                .distinct()
                .count();
        if (uniqueProjectIds != clients.size()) {
            throw new IllegalStateException("Each actor-link projectId may authorize exactly one service client");
        }
        long uniqueSecrets = clients.stream()
                .map(Client::secret)
                .distinct()
                .count();
        if (uniqueSecrets != clients.size()) {
            throw new IllegalStateException("Actor-link client secrets must be unique per service client");
        }
    }

    public record Client(String serviceId, String projectId, String secret) {
        public Client {
            serviceId = normalize(serviceId);
            projectId = normalize(projectId);
            secret = secret == null ? "" : secret;
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
