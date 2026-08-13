package io.janus.providers;

import java.net.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether an outbound destination is acceptable. Two questions are answered here: whether a
 * provider URL is well formed and reachable at registration time, and whether a concrete resolved
 * address may be connected to. The second question is asked again at connection time — see
 * {@link io.janus.gateway.GatewayHttpClientConfig} — because a name that resolved to a public
 * address during validation can resolve to a loopback address moments later.
 *
 * <p>Private addresses are refused by default, because a proxy able to reach one can be walked into
 * the infrastructure standing behind it. Two things lift that, and they are not equivalent.
 * {@code janus.gateway.allow-private-destinations} lifts it for the entire deployment and exists for
 * development; a provider's own {@code allowPrivateDestination} lifts it for that destination alone,
 * which is what a self-hosted service on the local network needs. Neither reaches loopback,
 * link-local, or the unspecified address: from inside a container the first is Janus and the OpenBao
 * it reads credentials from, and the second is where a cloud host answers with instance credentials.
 * Those stay refused unless the deployment-wide flag, which is not meant for production, says
 * otherwise.
 */
@Component
public class DestinationValidator {
    private final boolean allowPrivate;
    private final boolean privateDestinationsOffered;

    public DestinationValidator(
            @Value("${janus.gateway.allow-private-destinations:false}") boolean allowPrivate,
            @Value("${janus.gateway.private-destinations-enabled:false}") boolean privateDestinationsEnabled) {
        this.allowPrivate = allowPrivate;
        // The development flag already reaches everything this one admits, so a deployment that set
        // it would otherwise be refused a setting it has in effect anyway.
        this.privateDestinationsOffered = privateDestinationsEnabled || allowPrivate;
    }

    public boolean isAllowingPrivateDestinations() {
        return allowPrivate;
    }

    /**
     * Whether this deployment lets a destination be registered as being on a local network. False
     * unless asked for: most deployments proxy public APIs and never want the option, and the console
     * does not offer what the backend would refuse.
     */
    public boolean isOfferingPrivateDestinations() {
        return privateDestinationsOffered;
    }

    /**
     * Validates the shape of a provider URL without resolving it. The gateway calls this on every
     * request to confirm the stored destination is still well formed; the address itself is checked
     * at connection time, which a resolution here could not do any better.
     */
    public URI validateShape(String value) {
        return validateShape(value, false);
    }

    public URI validateShape(String value, boolean privateDestination) {
        return validate(value, privateDestination, false);
    }

    /** Validates a registered provider base URL, including where it currently resolves to. */
    public URI validate(String value) {
        return validate(value, false);
    }

    public URI validate(String value, boolean privateDestination) {
        return validate(value, privateDestination, !allowPrivate);
    }

    private URI validate(String value, boolean privateDestination, boolean resolve) {
        if (privateDestination && !privateDestinationsOffered)
            throw new IllegalArgumentException("This deployment does not allow destinations on a local network");

        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Provider URL is not a valid absolute URL");
        }
        if (!uri.isAbsolute()) throw new IllegalArgumentException("Provider URL must be absolute");
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        // A service on the local network is reached by address or by a name only that network
        // resolves, and no certificate authority will issue for either. Requiring HTTPS there would
        // not add a guarantee, it would just mean the destination cannot be registered at all.
        if (!https && !((allowPrivate || privateDestination) && http))
            throw new IllegalArgumentException("Provider URL must use HTTPS");
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
                if (isDisallowed(address, privateDestination))
                    throw new IllegalArgumentException(
                            privateDestination
                                    ? "Provider resolves to an address that is not on a local network"
                                    : "Provider resolves to a private or local address");
        }
        return uri;
    }

    public boolean isDisallowed(InetAddress address) {
        return isDisallowed(address, false);
    }

    /**
     * Rejects every address range that could reach infrastructure behind Janus rather than the
     * destination an administrator registered: loopback, link-local, site-local, carrier-grade NAT,
     * benchmarking, multicast, reserved, and the IPv6 transition ranges that embed an IPv4
     * destination.
     *
     * @param privateDestination whether this destination was registered as being on a local network,
     *     which admits the site-local, carrier-grade NAT, and unique-local ranges — and nothing else
     */
    public boolean isDisallowed(InetAddress address, boolean privateDestination) {
        if (allowPrivate) return false;

        // Refused whatever a destination declares. Nothing an administrator meant to proxy answers
        // here, and everything an attacker would ask for does.
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()) return true;

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isDisallowedIpv4(bytes, privateDestination);

        // fec0::/10, deprecated by RFC 3879. Nothing legitimate answers there.
        if (address.isSiteLocalAddress()) return true;

        int first = bytes[0] & 0xff;
        if (first == 0xfc || first == 0xfd) return !privateDestination; // fc00::/7 unique local
        if (isIpv4Mapped(bytes) || isNat64(bytes)) return isDisallowedIpv4(lastFourBytes(bytes), privateDestination);
        if (first == 0x20 && (bytes[1] & 0xff) == 0x02)
            return isDisallowedIpv4(
                    new byte[] {bytes[2], bytes[3], bytes[4], bytes[5]}, privateDestination); // 2002::/16 6to4
        return false;
    }

    private static boolean isDisallowedIpv4(byte[] b, boolean privateDestination) {
        int first = b[0] & 0xff, second = b[1] & 0xff;
        if (first == 0 || first == 127) return true; // this network, loopback
        if (first == 169 && second == 254) return true; // link-local, and cloud instance metadata
        if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return true; // IETF protocol assignments
        if (first == 198 && (second == 18 || second == 19)) return true; // benchmarking
        if (first >= 224) return true; // multicast, reserved, broadcast

        // What a destination on a local network may resolve to, once it says that is what it is.
        if (first == 10) return !privateDestination; // private
        if (first == 172 && second >= 16 && second <= 31) return !privateDestination; // private
        if (first == 192 && second == 168) return !privateDestination; // private
        if (first == 100 && second >= 64 && second <= 127) return !privateDestination; // carrier-grade NAT
        return false;
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
