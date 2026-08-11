package io.janus.audit;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * One bounded pass of the trim: at most {@code batch} rows older than {@code before}, removed and
     * counted so the caller knows whether another pass is due.
     *
     * <p>Native rather than JPQL, for the {@code limit} inside the subquery: a delete that names its
     * own ceiling is what makes the statement short whatever the table has grown to, and JPQL has no
     * way to express one. It reads the identifiers first so the index on the timestamp decides which
     * rows go, rather than the delete scanning to find out.
     *
     * <p>Each call is its own transaction. A trim is housekeeping, not an operation anybody is
     * waiting on: a hundred short transactions leave the gateway writing between them, where one
     * long one would hold its locks until it had removed a year of traffic.
     */
    @Modifying
    @Transactional
    @Query(
            value =
                    """
                    delete from audit_events
                    where id in (
                      select id from audit_events
                      where occurred_at < :before and action in (:actions)
                      limit :batch)""",
            nativeQuery = true)
    int deleteBeforeMatching(
            @Param("before") Instant before, @Param("actions") Collection<String> actions, @Param("batch") int batch);

    /** The same pass over everything the actions do <em>not</em> name; see {@link #deleteBeforeMatching}. */
    @Modifying
    @Transactional
    @Query(
            value =
                    """
                    delete from audit_events
                    where id in (
                      select id from audit_events
                      where occurred_at < :before and action not in (:actions)
                      limit :batch)""",
            nativeQuery = true)
    int deleteBeforeExcept(
            @Param("before") Instant before, @Param("actions") Collection<String> actions, @Param("batch") int batch);
}
