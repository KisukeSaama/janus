package io.janus.notifications;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.janus.accounts.AccessScope;
import io.janus.shared.NotFoundException;

/**
 * What the console reads to see what is coming.
 *
 * <p>The menu is deliberately not cleared by being looked at: a badge that disappears on a glance is
 * how a key expires anyway. Marking read and dismissing are both things an operator chooses to do.
 */
@Service
public class NotificationService {
    private final NotificationRepository repository;
    private final AccessScope scope;

    public NotificationService(NotificationRepository repository, AccessScope scope) {
        this.repository = repository;
        this.scope = scope;
    }

    @Transactional(readOnly = true)
    public NotificationFeed list(boolean unreadOnly) {
        UUID owner = scope.ownerFilter();
        var rows = unreadOnly
                ? repository.findByOwnerIdAndReadAtIsNullOrderByCreatedAtDesc(owner)
                : repository.findByOwnerIdOrderByCreatedAtDesc(owner);
        return new NotificationFeed(
                rows.stream().map(NotificationResponse::of).toList(), repository.countByOwnerIdAndReadAtIsNull(owner));
    }

    @Transactional
    public NotificationResponse markRead(UUID id) {
        var notification = require(id);
        notification.markRead(Instant.now());
        return NotificationResponse.of(notification);
    }

    @Transactional
    public NotificationFeed markAllRead() {
        repository.markAllRead(scope.ownerFilter(), Instant.now());
        return list(false);
    }

    /**
     * Dismissal removes the announcement, not the deadline. The date stays on the credential, and a
     * later stage is still announced when it arrives.
     */
    @Transactional
    public void dismiss(UUID id) {
        repository.delete(require(id));
    }

    /**
     * Scoped like everything else the console reads: somebody else's announcement is not a forbidden
     * one, it is one that does not exist from where the caller stands.
     */
    private Notification require(UUID id) {
        return repository
                .findById(id)
                .filter(notification -> notification.getOwnerId().equals(scope.ownerFilter()))
                .orElseThrow(() -> new NotFoundException("Notification not found"));
    }
}
