package io.janus.notifications;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * When Janus speaks about a deadline, and to whom.
 *
 * @param checkCron when the deadlines are swept, in Spring cron form
 * @param zone      the zone that schedule is read in; a deployment wants its own morning, not UTC's
 */
@ConfigurationProperties("janus.notifications")
public record NotificationProperties(
        @DefaultValue("0 15 7 * * *") String checkCron,
        @DefaultValue("UTC") String zone,
        @DefaultValue Expiry expiry,
        @DefaultValue Email email) {

    /**
     * The two thresholds. They are days rather than a single date because rotating a key is rarely
     * one person's work: the first says start arranging it, the second says it is now the work.
     *
     * @param noticeDays  first, quiet announcement this many days before the date
     * @param warningDays second, insistent one this many days before it
     */
    public record Expiry(@DefaultValue("30") int noticeDays, @DefaultValue("7") int warningDays) {
        public Expiry {
            if (noticeDays < 0 || warningDays < 0)
                throw new IllegalArgumentException("janus.notifications.expiry thresholds cannot be negative");
            if (warningDays > noticeDays)
                throw new IllegalArgumentException(
                        "janus.notifications.expiry.warning-days must not exceed notice-days; the warning is the later of the two");
        }
    }

    /**
     * Reaching someone who has not opened the console. Off by default: a deployment without an SMTP
     * relay must start and sweep normally, and silence is better than a stack trace every morning.
     *
     * @param recipients who is told; nobody listed means nothing is sent
     */
    public record Email(
            @DefaultValue("false") boolean enabled,
            @DefaultValue List<String> recipients,
            @DefaultValue("janus@localhost") String from,
            @DefaultValue("[Janus]") String subjectPrefix) {}
}
