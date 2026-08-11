package io.janus.audit;

import java.util.UUID;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The journal is read the way the registry is: an owner sees what happened to their own records and
 * nothing else. No role widens it — an administrator manages accounts, not other people's activity.
 *
 * <p>Entries written before ownership existed carry no owner and are therefore shown to nobody. They
 * remain in the table, which is append-only, and remain readable in the database itself.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findAllByOwnerIdOrderByOccurredAtDesc(UUID ownerId, Pageable pageable);

    Page<AuditEvent> findAllByOwnerIdAndOutcomeOrderByOccurredAtDesc(UUID ownerId, String outcome, Pageable pageable);
}
