package io.janus.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import io.janus.IntegrationTest;

/**
 * The one query the journal is read through, run against a real PostgreSQL.
 *
 * <p>A mocked repository cannot answer this: the outcome filter is optional inside the query itself,
 * and a driver that cannot infer the type of a null parameter fails at execution rather than at
 * startup — on the console's most-used screen, in production.
 */
class AuditWindowIT extends IntegrationTest {

    @Autowired
    private AuditEventRepository repository;

    @Test
    void readsAnOwnersEventsInsideTheWindow() {
        UUID owner = UUID.randomUUID();
        var success = repository.save(event(owner, AuditOutcome.SUCCESS));
        repository.save(event(owner, AuditOutcome.DENIED));
        repository.save(event(UUID.randomUUID(), AuditOutcome.SUCCESS));

        var page = repository.search(owner, null, past(), future(), PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(AuditEvent::getOwnerId).containsOnly(owner);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(AuditEvent::getId).contains(success.getId());
    }

    @Test
    void narrowsToOneOutcomeWhenAskedFor() {
        UUID owner = UUID.randomUUID();
        repository.save(event(owner, AuditOutcome.SUCCESS));
        var denied = repository.save(event(owner, AuditOutcome.DENIED));

        var page = repository.search(owner, AuditOutcome.DENIED.name(), past(), future(), PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(AuditEvent::getId).containsExactly(denied.getId());
    }

    /** The upper bound is exclusive, so a window that ends before an event stops short of it. */
    @Test
    void leavesOutWhatFallsOutsideTheWindow() {
        UUID owner = UUID.randomUUID();
        repository.save(event(owner, AuditOutcome.SUCCESS));

        var page = repository.search(
                owner, null, past(), Instant.now().minus(Duration.ofMinutes(30)), PageRequest.of(0, 50));

        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void readsTheSameWindowAsAListForAnExport() {
        UUID owner = UUID.randomUUID();
        repository.save(event(owner, AuditOutcome.SUCCESS));
        repository.save(event(owner, AuditOutcome.ERROR));

        var rows = repository.searchAll(owner, null, past(), future(), PageRequest.of(0, 10_000));

        assertThat(rows).hasSize(2);
        assertThat(AuditCsv.render(rows).lines()).hasSize(3);
    }

    private static Instant past() {
        return Instant.now().minus(Duration.ofHours(1));
    }

    private static Instant future() {
        return Instant.now().plus(Duration.ofHours(1));
    }

    private static AuditEvent event(UUID owner, AuditOutcome outcome) {
        var entry = new AuditEvent();
        entry.setOwnerId(owner);
        entry.setActorType(AuditActor.APPLICATION.name());
        entry.setAction(AuditAction.GATEWAY_REQUEST.name());
        entry.setOutcome(outcome.name());
        entry.setRequestMethod("GET");
        entry.setRequestPath("/spotify/v1/me");
        entry.setStatusCode(200);
        entry.setCorrelationId("correlation-" + UUID.randomUUID());
        return entry;
    }
}
