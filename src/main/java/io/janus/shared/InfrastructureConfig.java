package io.janus.shared;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.janus.audit.AuditRetentionProperties;
import io.janus.gateway.GatewayTrafficProperties;
import io.janus.notifications.NotificationProperties;
import io.janus.oauth.OAuthProperties;
import io.janus.openbao.OpenBaoProperties;

/**
 * Scheduling covers the jobs nobody triggers: the daily sweep over recorded expiry dates, and the
 * housekeeping that keeps the tables Janus only ever appends to from growing without end.
 *
 * <p>All of it is safe to run on more than one instance. The expiry sweep claims each announcement
 * in the database before making it, so the second instance finds nothing left to say; the
 * housekeeping deletes by age, so two instances agreeing on what has aged out is the point rather
 * than a conflict.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
    OpenBaoProperties.class,
    GatewayTrafficProperties.class,
    NotificationProperties.class,
    OAuthProperties.class,
    AuditRetentionProperties.class
})
public class InfrastructureConfig {}
