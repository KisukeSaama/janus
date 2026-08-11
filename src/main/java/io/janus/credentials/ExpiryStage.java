package io.janus.credentials;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import io.janus.audit.AuditAction;

/**
 * How close a stored secret is to the date recorded for it.
 *
 * The stages escalate and never move backwards while that date stands, which is what lets an
 * announcement be made once per stage instead of every day until someone acts. A deadline moved
 * further out is a different date, and rearms them all.
 */
public enum ExpiryStage {
    /** Far enough to plan the rotation, close enough to stop forgetting it. */
    NOTICE("INFO"),
    /** Close enough that the rotation is now the work, not a plan. */
    WARNING("WARN"),
    /** The date has passed. Whatever presents this secret is calling on borrowed time. */
    EXPIRED("CRITICAL");

    private final String severity;

    ExpiryStage(String severity) {
        this.severity = severity;
    }

    /** Tone the console and the outbound channels render this stage in. */
    public String severity() {
        return severity;
    }

    /** Audit action recorded when this stage is announced. */
    public AuditAction auditAction() {
        return switch (this) {
            case NOTICE -> AuditAction.CREDENTIAL_EXPIRY_NOTICE;
            case WARNING -> AuditAction.CREDENTIAL_EXPIRY_WARNING;
            case EXPIRED -> AuditAction.CREDENTIAL_EXPIRED;
        };
    }

    /**
     * The stage reached at {@code now}, or empty while the deadline is further off than the notice
     * window. A deadline exactly on a threshold counts as reached: the operator asked to be warned
     * seven days ahead, so seven days ahead is when it is said.
     */
    public static Optional<ExpiryStage> reached(Instant expiresAt, Instant now, int noticeDays, int warningDays) {
        if (expiresAt == null) return Optional.empty();
        if (!now.isBefore(expiresAt)) return Optional.of(EXPIRED);
        var remaining = Duration.between(now, expiresAt);
        if (remaining.compareTo(Duration.ofDays(warningDays)) <= 0) return Optional.of(WARNING);
        if (remaining.compareTo(Duration.ofDays(noticeDays)) <= 0) return Optional.of(NOTICE);
        return Optional.empty();
    }

    /**
     * Whole days left, rounded away from zero, negative once the date has passed. A key with eleven
     * hours to run has one day left rather than none: rounding down would announce "expires today"
     * on the morning of the day before.
     */
    public static long daysRemaining(Instant expiresAt, Instant now) {
        long minutes = Duration.between(now, expiresAt).toMinutes();
        long days = (Math.abs(minutes) + 1439) / 1440;
        return minutes < 0 ? -days : days;
    }
}
