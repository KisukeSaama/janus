package io.janus.openbao;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

import io.netty.channel.ChannelOption;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.*;
import reactor.netty.http.client.HttpClient;

/**
 * Minimal KV v2 integration. This client intentionally does not share the gateway's HTTP client:
 * OpenBao lives on the internal network, which the gateway client is configured to refuse.
 */
@Component
public class OpenBaoClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpenBaoClient.class);

    private static final Pattern SAFE_PATH = Pattern.compile("[a-zA-Z0-9/_-]+");
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final OpenBaoProperties properties;
    private final WebClient client;

    public OpenBaoClient(OpenBaoProperties properties) {
        this.properties = properties;
        if (properties.address() == null || properties.address().isBlank())
            throw new IllegalStateException("janus.openbao.address must be configured");
        warnIfInClear(properties.address());
        HttpClient httpClient = HttpClient.create()
                .followRedirect(false)
                .responseTimeout(Duration.ofSeconds(10))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);
        this.client = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .baseUrl(properties.address())
                .defaultHeader("X-Vault-Token", Objects.requireNonNullElse(properties.token(), ""))
                .defaultHeader("X-Vault-Request", "true")
                .build();
    }

    /**
     * Said once, at startup, when the vault is reached over plain HTTP across a network.
     *
     * <p>The token that unlocks every secret this deployment holds, and each secret read back with
     * it, travel over this address. On one host, with both containers on a network marked
     * {@code internal}, that is bounded by the host. The moment the two are on different machines it
     * is bounded by nothing, and there is no symptom: the deployment goes on working exactly as
     * before. So it is stated here rather than discovered later.
     *
     * <p>Loopback is exempt. There is no wire, and a developer running both locally does not need a
     * certificate to be told about.
     */
    private static void warnIfInClear(String address) {
        if (!address.regionMatches(true, 0, "http://", 0, 7)) return;
        String host = address.substring(7).split("[:/]", 2)[0];
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")) return;
        log.warn(
                "janus.openbao.address is {}, so the vault token and every secret read travel in clear. "
                        + "Terminate TLS at OpenBao before it stops sharing a host with this process",
                address);
    }

    public void write(String path, String value) {
        requireConfigured();
        exchange(
                client.post()
                        .uri("/v1/{mount}/data/{path}", properties.kvMount(), safe(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("data", Map.of("value", value))),
                "store");
    }

    @SuppressWarnings("unchecked")
    public String read(String path) {
        requireConfigured();
        Map<String, Object> response;
        try {
            response = client.get()
                    .uri("/v1/{mount}/data/{path}", properties.kvMount(), safe(path))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
        } catch (WebClientResponseException ex) {
            throw new IllegalStateException("OpenBao rejected the credential read with status "
                    + ex.getStatusCode().value());
        } catch (WebClientException ex) {
            throw new IllegalStateException("OpenBao is unreachable");
        }
        if (response == null) throw new IllegalStateException("OpenBao returned an empty response");
        var outer = (Map<String, Object>) response.get("data");
        var data = outer == null ? null : (Map<String, Object>) outer.get("data");
        var value = data == null ? null : data.get("value");
        if (!(value instanceof String secret) || secret.isBlank())
            throw new IllegalStateException("Credential secret is missing in OpenBao");
        return secret;
    }

    public void delete(String path) {
        requireConfigured();
        exchange(client.delete().uri("/v1/{mount}/metadata/{path}", properties.kvMount(), safe(path)), "delete");
    }

    private void exchange(WebClient.RequestHeadersSpec<?> spec, String operation) {
        try {
            spec.retrieve().toBodilessEntity().block();
        } catch (WebClientResponseException ex) {
            // The response body can echo request content, so only the status is surfaced.
            throw new IllegalStateException("OpenBao refused to " + operation + " the secret (status "
                    + ex.getStatusCode().value() + ")");
        } catch (WebClientException ex) {
            throw new IllegalStateException("OpenBao is unreachable");
        }
    }

    private String safe(String path) {
        if (path == null || !SAFE_PATH.matcher(path).matches() || path.contains("..") || path.contains("//"))
            throw new IllegalArgumentException("Invalid secret path");
        return path;
    }

    private void requireConfigured() {
        if (properties.token() == null || properties.token().isBlank())
            throw new IllegalStateException("OpenBao token is not configured");
    }
}
