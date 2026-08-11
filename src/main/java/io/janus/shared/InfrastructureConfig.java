package io.janus.shared;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.janus.gateway.GatewayTrafficProperties;
import io.janus.notifications.NotificationProperties;
import io.janus.oauth.OAuthProperties;
import io.janus.openbao.OpenBaoProperties;

/**
 * Scheduling exists for one job: the daily sweep over recorded expiry dates. It is safe to run on
 * more than one instance because the sweep claims each announcement in the database before making
 * it, so the second instance finds nothing left to say.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
    OpenBaoProperties.class,
    GatewayTrafficProperties.class,
    NotificationProperties.class,
    OAuthProperties.class
})
public class InfrastructureConfig {}
