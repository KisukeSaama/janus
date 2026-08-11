package io.janus.gateway;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

/**
 * What the caller receives, and what the audit trail records about how it was obtained.
 *
 * @param auditDetail short note naming the mechanism that answered, never any response content
 */
public record GatewayOutcome(
        HttpStatusCode status, HttpHeaders headers, byte[] body, CacheStatus cacheStatus, String auditDetail) {}
