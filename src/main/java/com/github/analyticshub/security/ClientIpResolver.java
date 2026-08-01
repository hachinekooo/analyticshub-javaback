package com.github.analyticshub.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a client address without trusting forwarding headers from arbitrary
 * internet clients. Forwarded hops are considered only when the immediate
 * peer belongs to the configured trusted-proxy list.
 */
@Component
public class ClientIpResolver {

    private final List<Network> trustedProxies;

    public ClientIpResolver(
            @Value("${app.security.trusted-proxies:127.0.0.1,::1}") String trustedProxyList
    ) {
        this.trustedProxies = parseNetworks(trustedProxyList);
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddress = normalizeIp(request.getRemoteAddr());
        if (remoteAddress == null) {
            return "unknown";
        }
        if (!isTrusted(remoteAddress)) {
            return remoteAddress;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            for (int index = hops.length - 1; index >= 0; index--) {
                String candidate = normalizeIp(hops[index]);
                if (candidate != null && !isTrusted(candidate)) {
                    return candidate;
                }
            }
        }

        String realIp = normalizeIp(request.getHeader("X-Real-IP"));
        if (realIp != null && !isTrusted(realIp)) {
            return realIp;
        }
        return remoteAddress;
    }

    private boolean isTrusted(String address) {
        InetAddress parsed = parseLiteral(address);
        return parsed != null && trustedProxies.stream().anyMatch(network -> network.contains(parsed));
    }

    private static List<Network> parseNetworks(String configured) {
        List<Network> networks = new ArrayList<>();
        if (configured == null || configured.isBlank()) {
            return networks;
        }
        for (String raw : configured.split(",")) {
            String value = raw.strip();
            if (value.isEmpty()) {
                continue;
            }
            String[] parts = value.split("/", 2);
            InetAddress address = parseLiteral(parts[0]);
            if (address == null) {
                throw new IllegalStateException("Invalid trusted proxy address");
            }
            int maxPrefix = address.getAddress().length * Byte.SIZE;
            int prefix = maxPrefix;
            if (parts.length == 2) {
                try {
                    prefix = Integer.parseInt(parts[1]);
                } catch (NumberFormatException exception) {
                    throw new IllegalStateException("Invalid trusted proxy CIDR", exception);
                }
            }
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalStateException("Invalid trusted proxy CIDR prefix");
            }
            networks.add(new Network(address.getAddress(), prefix));
        }
        return List.copyOf(networks);
    }

    private static String normalizeIp(String value) {
        InetAddress address = parseLiteral(value);
        return address == null ? null : address.getHostAddress();
    }

    private static InetAddress parseLiteral(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        // Prevent InetAddress from performing DNS for untrusted header values.
        if (!normalized.matches("[0-9A-Fa-f:.]+")
                || (!normalized.contains(":") && !normalized.matches("[0-9.]+"))) {
            return null;
        }
        try {
            return InetAddress.getByName(normalized);
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private record Network(byte[] address, int prefixLength) {
        private boolean contains(InetAddress candidate) {
            byte[] candidateBytes = candidate.getAddress();
            if (candidateBytes.length != address.length) {
                return false;
            }
            int wholeBytes = prefixLength / Byte.SIZE;
            int remainingBits = prefixLength % Byte.SIZE;
            for (int index = 0; index < wholeBytes; index++) {
                if (candidateBytes[index] != address[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return (candidateBytes[wholeBytes] & mask) == (address[wholeBytes] & mask);
        }
    }
}
