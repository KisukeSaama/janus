package io.janus.security;

import io.janus.shared.Environment;
import java.util.UUID;

public record GatewayPrincipal(UUID applicationId, String applicationName, Environment environment) { }
