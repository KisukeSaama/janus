package io.janus.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The journal is read the way the registry is: an owner sees what happened to their own records and
 * nothing else. No role widens it — an administrator manages accounts, not other people's activity.
 *
 * <p>Entries written before ownership existed carry no owner and are therefore shown to nobody. They
 * remain in the table, which is append-only, and remain readable in the database itself.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * One window over the journal, which every read here narrows the same way: an owner, a time span,
     * and optionally an outcome. The span is always bounded rather than optional — the controller
     * substitutes the widest possible bounds when the reader asked for none — so the query has one
     * shape, and the index on the timestamp is used whether or not a range was named.
     */
    String WINDOW =
            """
            select e from AuditEvent e
            where e.ownerId = :ownerId
              and e.occurredAt >= :from
              and e.occurredAt < :until
              and (:outcome is null or e.outcome = :outcome)
            order by e.occurredAt desc""";

    @Query(WINDOW)
    Page<AuditEvent> search(
            @Param("ownerId") UUID ownerId,
            @Param("outcome") String outcome,
            @Param("from") Instant from,
            @Param("until") Instant until,
            Pageable pageable);

    /**
     * The same window read as a list, for an export. The caller bounds it with a {@link Pageable}; a
     * list return skips the count query a {@link Page} would run, which an export has no use for.
     */
    @Query(WINDOW)
    List<AuditEvent> searchAll(
            @Param("ownerId") UUID ownerId,
            @Param("outcome") String outcome,
            @Param("from") Instant from,
            @Param("until") Instant until,
            Pageable pageable);
}
