package io.janus.providers;

import static org.assertj.core.api.Assertions.*;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

class DestinationValidatorTest {
    private final DestinationValidator strict = new DestinationValidator(false);
    private final DestinationValidator permissive = new DestinationValidator(true);

    @Test
    void rejectsLocalAndNonHttpsDestinations() {
        assertThatThrownBy(() -> strict.validate("http://example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.validate("https://127.0.0.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.validate("https://user@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void permitsExplicitPrivateDestinationsOnlyInOptInMode() {
        assertThat(permissive.validate("http://localhost:9000").getHost()).isEqualTo("localhost");
    }

    @Test
    void rejectsUrlsCarryingAQueryOrFragment() {
        assertThatThrownBy(() -> strict.validate("https://api.example.com?token=x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.validate("https://api.example.com#part"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTraversalInTheBasePath() {
        assertThatThrownBy(() -> strict.validate("https://api.example.com/../internal"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonAbsoluteAndUnparsableUrls() {
        assertThatThrownBy(() -> strict.validate("/relative")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strict.validate("https://exa mple.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shapeValidationSkipsResolutionButKeepsEveryOtherRule() {
        assertThat(strict.validateShape("https://host.invalid/base").getHost()).isEqualTo("host.invalid");
        assertThatThrownBy(() -> strict.validateShape("http://host.invalid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blocksTheAddressRangesThatReachInternalInfrastructure() throws UnknownHostException {
        for (String literal : new String[] {
            "127.0.0.1",
            "0.0.0.0",
            "10.1.2.3",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "169.254.169.254", // cloud instance metadata
            "100.64.0.1", // carrier-grade NAT
            "192.0.0.1", // IETF protocol assignments
            "198.18.0.1", // benchmarking
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "::",
            "fc00::1",
            "fd12::1",
            "fe80::1",
            "ff02::1"
        }) {
            assertThat(strict.isDisallowed(InetAddress.getByName(literal)))
                    .withFailMessage("expected %s to be blocked", literal)
                    .isTrue();
        }
    }

    @Test
    void blocksIpv6FormsThatEmbedAPrivateIpv4Address() throws UnknownHostException {
        // ::ffff:127.0.0.1 — an IPv4-mapped loopback address
        assertThat(strict.isDisallowed(ipv6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 127, 0, 0, 1)))
                .isTrue();
        // 64:ff9b::10.1.1.1 — NAT64 translation of a private address
        assertThat(strict.isDisallowed(ipv6(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 10, 1, 1, 1)))
                .isTrue();
        // 2002:c0a8:0101:: — 6to4 encapsulation of 192.168.1.1
        assertThat(strict.isDisallowed(ipv6(0x20, 0x02, 192, 168, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
                .isTrue();
    }

    @Test
    void allowsPublicAddresses() throws UnknownHostException {
        assertThat(strict.isDisallowed(InetAddress.getByName("93.184.216.34"))).isFalse();
        assertThat(strict.isDisallowed(InetAddress.getByName("2606:2800:220:1::1")))
                .isFalse();
    }

    @Test
    void theOptInModeDisablesAddressFiltering() throws UnknownHostException {
        assertThat(permissive.isDisallowed(InetAddress.getByName("127.0.0.1"))).isFalse();
    }

    /** Builds an address from raw bytes, so the test does not depend on how literals are parsed. */
    private static InetAddress ipv6(int... octets) throws UnknownHostException {
        byte[] address = new byte[16];
        for (int i = 0; i < 16; i++) address[i] = (byte) octets[i];
        return InetAddress.getByAddress(address);
    }
}
