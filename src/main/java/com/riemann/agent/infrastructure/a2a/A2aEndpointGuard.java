package com.riemann.agent.infrastructure.a2a;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Validates outbound A2A endpoint URLs before {@link A2aClient} sends a request.
 *
 * <p>The endpoint comes from Nacos service discovery, i.e. from data outside the process. Passing
 * it straight to {@code WebClient.uri(endpoint)} lets a poisoned registration point delegation at
 * cloud metadata services or any internal host (SSRF). This guard enforces:
 *
 * <ul>
 *   <li>only {@code http} / {@code https}, no userinfo, a non-empty host;
 *   <li>when {@code endpoint-allowlist} is configured, the host must match it exactly;
 *   <li>otherwise, IP literals that are link-local (169.254.0.0/16, incl. the metadata address
 *       169.254.169.254), multicast or unspecified are rejected. Loopback and private ranges
 *       (127/8, 10/8) are allowed because the bundled single-host demo and Nacos-discovered peers
 *       use them; production deployments should set an explicit allowlist.
 * </ul>
 */
public final class A2aEndpointGuard {

    private final List<String> allowedHosts;

    public A2aEndpointGuard(List<String> allowedHosts) {
        this.allowedHosts =
                allowedHosts == null
                        ? List.of()
                        : allowedHosts.stream()
                                .filter(h -> h != null && !h.isBlank())
                                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                                .toList();
    }

    /**
     * @return the parsed, validated URI
     */
    public URI validate(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("A2A endpoint is empty");
        }
        URI uri;
        try {
            uri = new URI(endpoint.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "A2A endpoint is not a valid URI: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("A2A endpoint must use http or https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("A2A endpoint must not contain credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("A2A endpoint has no host");
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (!allowedHosts.isEmpty()) {
            if (!allowedHosts.contains(normalized)) {
                throw new IllegalArgumentException(
                        "A2A endpoint host is not in the allowlist: " + host);
            }
            return uri;
        }
        if (isBlockedAddress(normalized)) {
            throw new IllegalArgumentException("A2A endpoint points to a blocked address: " + host);
        }
        return uri;
    }

    private static boolean isBlockedAddress(String host) {
        if (!isIpLiteral(host)) {
            // Hostnames are not resolved here (no DNS on the hot path). Names such as "localhost"
            // are allowed so the bundled single-host demo keeps working; strict deployments must
            // configure endpoint-allowlist.
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true; // IPv6 literal
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }
}
