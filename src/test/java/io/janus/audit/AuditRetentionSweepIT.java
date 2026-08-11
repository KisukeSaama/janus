package io.janus.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.janus.IntegrationTest;

/**
 * The trim, run against a real PostgreSQL.
 *
 * <p>A mocked repository cannot answer what is asked here. The two deletes are native statements
 * carrying a collection into an {@code in} list and a {@code limit} inside a subquery — the kind of
 * SQL that is accepted at startup and fails the first night it runs, unattended, on the one table
 * nothing else can rebuild. What this asserts is that they execute, that they remove by age and by
 * nothing else, and that the batch ceiling is really a ceiling.
 *
 * <p>Rows are inserted through JDBC rather than through the entity: {@link AuditEvent} stamps its own
 * timestamp on construction, which is right for a journal and useless for testing one.
 */
class AuditRetentionSweepIT extends IntegrationTest {

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private static final Instant NOW = Instant.now();

    private AuditRetentionSweep sweep(int trafficDays, int administrativeDays, int batchSize) {
        return new AuditRetentionSweep(
                repository,
                new AuditRetentionProperties(trafficDays, administrativeDays, "0 0 0 1 1 *", "UTC", batchSize));
    }

    /** Every test owns its rows, so a shared database and a shared suite cannot cross them. */
    private UUID insert(UUID owner, AuditAction action, Duration age) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into audit_events
                  (id, occurred_at, actor_type, action, outcome, correlation_id, owner_id)
                values (?, ?, ?, ?, ?, ?, ?)""",
                id,
                java.sql.Timestamp.from(NOW.minus(age)),
                AuditActor.APPLICATION.name(),
                action.name(),
                AuditOutcome.SUCCESS.name(),
                "retention-it",
                owner);
        return id;
    }

    private List<UUID> remaining(UUID owner) {
        return jdbc.query(
                "select id from audit_events where owner_id = ?",
                (row, index) -> row.getObject("id", UUID.class),
                owner);
    }

    @Test
    void removesTrafficPastItsAgeAndKeepsTheRest() {
        UUID owner = UUID.randomUUID();
        UUID old = insert(owner, AuditAction.GATEWAY_REQUEST, Duration.ofDays(45));
        UUID recent = insert(owner, AuditAction.GATEWAY_REQUEST, Duration.ofDays(5));

        sweep(30, 365, 5000).trim(NOW);

        assertThat(remaining(owner)).containsExactly(recent).doesNotContain(old);
    }

    /**
     * The point of two ages: a month of traffic goes and the administrative record of the same week
     * stays, because it is what somebody comes back to long afterwards.
     */
    @Test
    void keepsAdministrativeHistoryLongAfterTheTrafficAroundItIsGone() {
        UUID owner = UUID.randomUUID();
        UUID call = insert(owner, AuditAction.GATEWAY_REQUEST, Duration.ofDays(120));
        UUID refused = insert(owner, AuditAction.ADMIN_AUTHENTICATION, Duration.ofDays(120));
        UUID created = insert(owner, AuditAction.CREDENTIAL_CREATED, Duration.ofDays(120));
        UUID purge = insert(owner, AuditAction.GATEWAY_CACHE_PURGED, Duration.ofDays(120));
        UUID signedIn = insert(owner, AuditAction.ACCOUNT_SIGNED_IN, Duration.ofDays(120));

        sweep(30, 365, 5000).trim(NOW);

        // The cache purge is an administrator's doing despite its name, and the sign-in that worked
        // is the record somebody comes back for; the refused attempt beside it ages with the traffic.
        assertThat(remaining(owner))
                .containsExactlyInAnyOrder(created, purge, signedIn)
                .doesNotContain(call, refused);
    }

    @Test
    void removesAdministrativeHistoryOnceItHasAgedOutToo() {
        UUID owner = UUID.randomUUID();
        UUID ancient = insert(owner, AuditAction.CREDENTIAL_CREATED, Duration.ofDays(400));
        UUID lastYear = insert(owner, AuditAction.CREDENTIAL_CREATED, Duration.ofDays(300));

        sweep(30, 365, 5000).trim(NOW);

        assertThat(remaining(owner)).containsExactly(lastYear).doesNotContain(ancient);
    }

    /** Zero means this deployment ships the journal somewhere else and Janus touches nothing. */
    @Test
    void leavesEverythingAloneWhenBothAgesAreZero() {
        UUID owner = UUID.randomUUID();
        insert(owner, AuditAction.GATEWAY_REQUEST, Duration.ofDays(1000));
        insert(owner, AuditAction.CREDENTIAL_CREATED, Duration.ofDays(1000));

        assertThat(sweep(0, 0, 5000).trim(NOW)).isZero();
        assertThat(remaining(owner)).hasSize(2);
    }

    /**
     * The batch ceiling is what keeps the statement short whatever the table has grown to, and the
     * loop around it is what makes a backlog still go in one run.
     */
    @Test
    void removesABacklogABatchAtATime() {
        UUID owner = UUID.randomUUID();
        for (int i = 0; i < 7; i++) insert(owner, AuditAction.GATEWAY_REQUEST, Duration.ofDays(45 + i));

        assertThat(sweep(30, 0, 2).trim(NOW)).isEqualTo(7);
        assertThat(remaining(owner)).isEmpty();
    }

    /** Age decides, and nothing else: not who acted, not how it turned out. */
    @Test
    void doesNotChooseWhichEventsDisappear() {
        UUID owner = UUID.randomUUID();
        UUID denied = UUID.randomUUID();
        jdbc.update(
                """
                insert into audit_events
                  (id, occurred_at, actor_type, action, outcome, correlation_id, owner_id)
                values (?, ?, ?, ?, ?, ?, ?)""",
                denied,
                java.sql.Timestamp.from(NOW.minus(Duration.ofDays(5))),
                AuditActor.APPLICATION.name(),
                AuditAction.GATEWAY_REQUEST.name(),
                AuditOutcome.DENIED.name(),
                "retention-it",
                owner);

        sweep(30, 365, 5000).trim(NOW);

        assertThat(remaining(owner)).containsExactly(denied);
    }
}
