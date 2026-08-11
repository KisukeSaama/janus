package io.janus.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the journal from being the thing that fills the disk.
 *
 * <p>The stream is append-only by design and nothing else ever deletes from it, so without this the
 * table only grows, and it grows at the rate the gateway is used rather than at the rate anybody
 * administers anything. This is the one place allowed to remove from it, on a schedule, by age, and
 * by nothing else — no filter on who acted or on what the outcome was, because a trim that could
 * choose which events disappear is not housekeeping.
 *
 * <p>Traffic and everything else age at their own rates; see {@link AuditRetentionProperties} for
 * why. Both are removed in bounded batches, so the first run on an installation that has been
 * recording since day one behaves like every run after it.
 *
 * <p>Nothing is written to the journal about the trim. A system event carries no owner and is
 * therefore shown to nobody in the console, so it would only be another row nobody reads; the count
 * goes to the application log, where whoever is asking about disk is already looking.
 */
@Service
public class AuditRetentionSweep {
    private static final Logger log = LoggerFactory.getLogger(AuditRetentionSweep.class);

    /**
     * The high-volume half: everything written once per attempt at the door.
     *
     * <p>A proxied call, and the three refusals. The refusals belong here for the same reason the
     * calls do rather than despite being security records: they are written by whoever is knocking,
     * so a scripted flood against the sign-in form or the token endpoint would otherwise write at
     * request rate into the half kept for a year. Thirty days of rejected attempts is a forensic
     * window; a year of them is somebody else's disk budget. What was actually granted — a sign-in
     * that succeeded, a refresh token replayed — is administrative and stays.
     *
     * <p>Held as the enum names rather than as literals in the SQL, so renaming one is a compile
     * error here instead of a half that silently stops matching.
     */
    private static final List<String> TRAFFIC = Stream.of(
                    AuditAction.GATEWAY_REQUEST,
                    AuditAction.GATEWAY_AUTHENTICATION,
                    AuditAction.ADMIN_AUTHENTICATION,
                    AuditAction.OAUTH_TOKEN_ISSUED)
            .map(Enum::name)
            .toList();

    /**
     * How many batches one run will do before leaving the rest for the next. A backstop, not a
     * budget: at the default batch size it is five million rows a night, far past any real backlog,
     * and it exists so a delete that keeps reporting a full batch cannot loop until morning.
     */
    private static final int MAX_BATCHES = 1_000;

    private final AuditEventRepository repository;
    private final AuditRetentionProperties properties;

    AuditRetentionSweep(AuditEventRepository repository, AuditRetentionProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Not transactional, deliberately: each batch commits on its own inside the repository, and a
     * transaction opened here would undo exactly the property that makes the trim safe to run while
     * Janus is serving traffic.
     */
    @Scheduled(cron = "${janus.audit.retention.sweep-cron}", zone = "${janus.audit.retention.zone}")
    public void sweep() {
        trim(Instant.now());
    }

    /**
     * Removes what has aged out as of {@code now}, and answers how many rows went.
     *
     * <p>{@code now} is a parameter so the boundaries can be exercised without waiting a month for
     * one.
     */
    public int trim(Instant now) {
        int traffic = purge(
                "traffic events",
                properties.trafficDays(),
                (before, batch) -> repository.deleteBeforeMatching(before, TRAFFIC, batch),
                now);
        int rest = purge(
                "administrative events",
                properties.administrativeDays(),
                (before, batch) -> repository.deleteBeforeExcept(before, TRAFFIC, batch),
                now);
        return traffic + rest;
    }

    /** One age limit, applied a batch at a time until a short batch says there is nothing left. */
    private int purge(String what, int days, Pass pass, Instant now) {
        if (days == 0) return 0; // Kept forever: this deployment sends the journal somewhere else.

        var before = now.minus(Duration.ofDays(days));
        int size = properties.batchSize();
        int total = 0;

        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int dropped = pass.removeUpTo(before, size);
            total += dropped;
            // A batch that came back short is the last one there was: nothing older remains.
            if (dropped < size) {
                if (total > 0) log.info("Trimmed {} {} older than {} day(s)", total, what, days);
                return total;
            }
        }

        log.warn("Stopped after trimming {} {}; the rest is left for the next sweep", total, what);
        return total;
    }

    /** One bounded delete, so the two age limits differ only in which rows they name. */
    @FunctionalInterface
    private interface Pass {
        int removeUpTo(Instant before, int batch);
    }
}
