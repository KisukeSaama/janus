package io.janus.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long the journal is kept before it starts costing more than it is worth.
 *
 * <p>Two durations rather than one, because the stream holds two very different things. Traffic is
 * whatever is written once per attempt at the door — a proxied call, and any request refused before
 * it got in. Its volume is decided by whoever is knocking rather than by anything a deployment
 * configures, which is what makes a year of it a year of disk, and it is also the half read soonest
 * after it happens: the console offers nothing wider than a month without asking for exact dates.
 * Everything else — a credential created, a grant changed, somebody signing in successfully, a
 * consent withdrawn, a refresh token replayed — arrives a handful of times a day and is the half
 * somebody comes back to months later to answer who changed what. It is kept far longer for a
 * fraction of the space.
 *
 * <p>Zero on either means keep forever, for a deployment that ships the journal somewhere else and
 * wants Janus to touch nothing.
 *
 * @param trafficDays how long a call, or a refusal to admit one, is kept
 * @param administrativeDays how long everything else is kept
 * @param sweepCron when the trim runs, in Spring cron form; the quiet hours, by default
 * @param zone the zone that schedule is read in
 * @param batchSize how many rows one statement removes, so a first run against a table that has
 *     been accumulating since installation is a sequence of short transactions rather than one long
 *     one holding locks over the table the gateway is still writing to
 */
@ConfigurationProperties("janus.audit.retention")
public record AuditRetentionProperties(
        @DefaultValue("30") int trafficDays,
        @DefaultValue("365") int administrativeDays,
        @DefaultValue("0 45 3 * * *") String sweepCron,
        @DefaultValue("UTC") String zone,
        @DefaultValue("5000") int batchSize) {

    public AuditRetentionProperties {
        if (trafficDays < 0)
            throw new IllegalArgumentException(
                    "janus.audit.retention.traffic-days cannot be negative; 0 keeps forever");
        if (administrativeDays < 0)
            throw new IllegalArgumentException(
                    "janus.audit.retention.administrative-days cannot be negative; 0 keeps forever");
        if (batchSize < 1) throw new IllegalArgumentException("janus.audit.retention.batch-size must be positive");
    }
}
