package io.janus.notifications;

import java.time.Instant;
import java.util.*;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import io.janus.credentials.ExpiryStage;

/**
 * The feed is read the way the registry is: an announcement is about somebody's secret, and it is
 * shown to that somebody. The unscoped finders belong to the daily sweep, which has no signed-in
 * person and has to see every standing announcement to withdraw the ones that stopped being true.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    List<Notification> findByOwnerIdAndReadAtIsNullOrderByCreatedAtDesc(UUID ownerId);

    long countByOwnerIdAndReadAtIsNull(UUID ownerId);

    /** Unscoped: the sweep's own lookup, one credential and stage at a time. */
    Optional<Notification> findByCredentialIdAndStage(UUID credentialId, ExpiryStage stage);

    /** Cleared automatically so the list read straight afterwards reflects the update, not the cache. */
    @Modifying(clearAutomatically = true)
    @Query("update Notification n set n.readAt = :readAt where n.readAt is null and n.ownerId = :owner")
    int markAllRead(@Param("owner") UUID owner, @Param("readAt") Instant readAt);
}
