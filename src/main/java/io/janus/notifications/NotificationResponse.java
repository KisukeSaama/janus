package io.janus.notifications;

import java.time.Instant;
import java.util.UUID;

import io.janus.credentials.ExpiryStage;

/**
 * One announcement, carrying the stage and the deadline rather than a countdown: how many days are
 * left is derived where it is displayed, so a page left open overnight cannot keep claiming seven
 * days when there are six.
 */
public record NotificationResponse(
        UUID id,
        ExpiryStage stage,
        String severity,
        UUID credentialId,
        String credentialName,
        String providerName,
        Instant expiresAt,
        Instant createdAt,
        Instant readAt) {

    public static NotificationResponse of(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getStage(),
                notification.getStage().severity(),
                notification.getCredentialId(),
                notification.getCredentialName(),
                notification.getProviderName(),
                notification.getExpiresAt(),
                notification.getCreatedAt(),
                notification.getReadAt());
    }
}
