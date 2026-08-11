package io.janus.providers;

import java.net.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether an outbound destination is acceptable. Two questions are answered here: whether a
 * provider URL is well formed and public at registration time, and whether a concrete resolved
 * address may be connected to. The second question is asked again at connection time — see
 * {@link io.janus.gateway.GatewayHttpClientConfig} — because a name that resolved to a public
 * address during validation can resolve to a loopback address moments later.
 */
@Component
public class DestinationValidator {
    private final boolean allowPrivate;

    public DestinationValidator(@Value("${janus.gateway.allow-private-destinations:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public boolean isAllowingPrivateDestinations() {
        return allowPrivate;
    }

    /**
     * Validates the shape of a provider URL without resolving it. The gateway calls this on every
     * request to confirm the stored destination is still well formed; the address itself is checked
     * at connection time, which a resolution here could not do any better.
     */
    public URI validateShape(String value) {
        return validate(value, false);
    }

    /** Validates a registered provider base URL, including where it currently resolves to. */
    public URI validate(String value) {
        return validate(value, !allowPrivate);
    }

    private URI validate(String value, boolean resolve) {
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Provider URL is not a valid absolute URL");
        }
        if (!uri.isAbsolute()) throw new IllegalArgumentException("Provider URL must be absolute");
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        if (!https && !(allowPrivate && http)) throw new IllegalArgumentException("Provider URL must use HTTPS");
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException(
                    "Provider URL must be an absolute host URL without credentials, query, or fragment");
        if (uri.getPath() != null
                && (uri.getPath().contains("..") || uri.getPath().contains("//")))
            throw new IllegalArgumentException("Provider URL path must not contain traversal segments");

        if (resolve) {
            InetAddress[] resolved;
            try {
                resolved = InetAddress.getAllByName(uri.getHost());
            } catch (UnknownHostException ex) {
                throw new IllegalArgumentException("Provider host cannot be resolved");
            }
            for (var address : resolved)
                if (isDisallowed(address))
                    throw new IllegalArgumentException("Provider resolves to a private or local address");
        }
        return uri;
    }

    /**
     * Rejects every address range that could reach infrastructure behind Janus rather than the public
     * internet: loopback, link-local, site-local, carrier-grade NAT, benchmarking, multicast, reserved,
     * and the IPv6 transition ranges that embed an IPv4 destination.
     */
    public boolean isDisallowed(InetAddress address) {
        if (allowPrivate) return false;
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) return true;

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isDisallowedIpv4(bytes);

        int first = bytes[0] & 0xff;
        if (first == 0xfc || first == 0xfd) return true; // fc00::/7 unique local
        if (isIpv4Mapped(bytes) || isNat64(bytes)) return isDisallowedIpv4(lastFourBytes(bytes));
        if (first == 0x20 && (bytes[1] & 0xff) == 0x02)
            return isDisallowedIpv4(new byte[] {bytes[2], bytes[3], bytes[4], bytes[5]}); // 2002::/16 6to4
        return false;
    }

    private static boolean isDisallowedIpv4(byte[] b) {
        int first = b[0] & 0xff, second = b[1] & 0xff;
        if (first == 0 || first == 127) return true; // this network, loopback
        if (first == 10) return true; // private
        if (first == 100 && second >= 64 && second <= 127) return true; // carrier-grade NAT
        if (first == 169 && second == 254) return true; // link-local
        if (first == 172 && second >= 16 && second <= 31) return true; // private
        if (first == 192 && second == 168) return true; // private
        if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return true; // IETF protocol assignments
        if (first == 198 && (second == 18 || second == 19)) return true; // benchmarking
        return first >= 224; // multicast, reserved, broadcast
    }

    private static boolean isIpv4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    /** 64:ff9b::/96 and 64:ff9b:1::/48 embed an IPv4 destination for NAT64 translation. */
    private static boolean isNat64(byte[] b) {
        return (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
    }

    private static byte[] lastFourBytes(byte[] b) {
        return new byte[] {b[12], b[13], b[14], b[15]};
    }
}
