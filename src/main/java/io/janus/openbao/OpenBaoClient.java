package io.janus.openbao;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.*;

@Component
public class OpenBaoClient {
    private final OpenBaoProperties properties;
    private final WebClient client;

    public OpenBaoClient(OpenBaoProperties properties, WebClient.Builder builder) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.address()).defaultHeader("X-Vault-Token", properties.token()).build();
    }

    public void write(String path, String value) {
        requireConfigured();
        client.post().uri("/v1/{mount}/data/{path}", properties.kvMount(), safe(path))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("data", Map.of("value", value)))
                .retrieve().toBodilessEntity().block();
    }

    @SuppressWarnings("unchecked")
    public String read(String path) {
        requireConfigured();
        Map<String,Object> response = client.get().uri("/v1/{mount}/data/{path}", properties.kvMount(), safe(path))
                .retrieve().bodyToMono(Map.class).block();
        if (response == null) throw new IllegalStateException("OpenBao returned an empty response");
        var outer = (Map<String,Object>) response.get("data");
        var data = outer == null ? null : (Map<String,Object>) outer.get("data");
        var value = data == null ? null : data.get("value");
        if (!(value instanceof String secret) || secret.isBlank()) throw new IllegalStateException("Credential secret is missing in OpenBao");
        return secret;
    }

    public void delete(String path) {
        requireConfigured();
        client.delete().uri("/v1/{mount}/metadata/{path}", properties.kvMount(), safe(path)).retrieve().toBodilessEntity().block();
    }

    private String safe(String path) {
        if (path == null || !path.matches("[a-zA-Z0-9/_-]+") || path.contains("..")) throw new IllegalArgumentException("Invalid secret path");
        return path;
    }
    private void requireConfigured() { if (properties.token() == null || properties.token().isBlank()) throw new IllegalStateException("OpenBao token is not configured"); }
}
