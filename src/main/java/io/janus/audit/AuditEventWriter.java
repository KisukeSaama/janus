package io.janus.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits one event in its own transaction.
 *
 * <p>A separate bean rather than a method on {@link AuditService}: a transactional method called
 * from inside its own class bypasses the proxy, and this one is also called from another thread,
 * where there is no surrounding transaction to join in the first place.
 */
@Component
class AuditEventWriter {
    private final AuditEventRepository repository;

    AuditEventWriter(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Its own transaction, so an event survives the rollback of whatever was being attempted. That
     * is the whole point of an audit trail: a denied or failed operation is exactly the one worth
     * having a record of.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void write(AuditEvent entry) {
        repository.save(entry);
    }
}
