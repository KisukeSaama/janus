package io.janus.notifications;

import java.time.*;
import java.util.*;

import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

import io.janus.audit.AuditService;
import io.janus.credentials.*;

/**
 * The part of Janus that remembers so nobody has to.
 *
 * Once a day it reads the deadlines the operator recorded and says what has become true since
 * yesterday. It says each thing once: a stage is claimed in the credential's own row before the
 * announcement is written, so a second run, a restart, or a second instance on the same schedule
 * finds nothing left to claim and stays quiet.
 */
@Service
public class CredentialExpiryMonitor {
    private static final Logger log = LoggerFactory.getLogger(CredentialExpiryMonitor.class);

    private final CredentialRepository credentials;
    private final NotificationRepository notifications;
    private final ExpiryMailer mailer;
    private final AuditService audit;
    private final NotificationProperties properties;

    public CredentialExpiryMonitor(
            CredentialRepository credentials,
            NotificationRepository notifications,
            ExpiryMailer mailer,
            AuditService audit,
            NotificationProperties properties) {
        this.credentials = credentials;
        this.notifications = notifications;
        this.mailer = mailer;
        this.audit = audit;
        this.properties = properties;
    }

    @Scheduled(cron = "${janus.notifications.check-cron}", zone = "${janus.notifications.zone}")
    @Transactional
    public void sweep() {
        var raised = announce(Instant.now());
        if (raised.isEmpty()) return;
        // Nothing leaves the building before the announcement is durably recorded: a refused SMTP
        // relay must not roll back what the console is already showing, and a mail sent for an
        // announcement that then rolled back would point at nothing.
        var sent = List.copyOf(raised);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                mailer.send(sent);
            }
        });
    }

    /**
     * Raises what the date makes true, once per stage per credential. Runs inside the caller's
     * transaction; {@code now} is a parameter so the boundaries can be exercised without waiting
     * for them.
     */
    public List<Notification> announce(Instant now) {
        var thresholds = properties.expiry();
        var horizon = now.plus(Duration.ofDays(thresholds.noticeDays()));
        var raised = new ArrayList<Notification>();

        withdrawSuperseded(now, thresholds);

        for (var credential : credentials.findExpiringBy(horizon)) {
            var stage = ExpiryStage.reached(
                            credential.getExpiresAt(), now, thresholds.noticeDays(), thresholds.warningDays())
                    .orElse(null);
            if (stage == null) continue;
            if (credentials.claimExpiryStage(credential.getId(), stage) == 0) continue;

            // A stage reached again after the deadline moved restates its row rather than adding a
            // second one; the operator needs the sentence once, with today's date on it.
            var notification = notifications
                    .findByCredentialIdAndStage(credential.getId(), stage)
                    .map(existing -> {
                        existing.restate(credential, now);
                        return existing;
                    })
                    .orElseGet(() -> Notification.of(credential, stage, now));
            raised.add(notifications.save(notification));

            audit.recordSystem(stage.auditAction(), credential.getProvider().getId(), detail(credential, stage, now));
        }

        if (!raised.isEmpty()) log.info("Announced {} credential expiry stage(s)", raised.size());
        return raised;
    }

    /**
     * Withdraws every announcement the current dates no longer support, so a key carries exactly one
     * line: the stage it is at now.
     *
     * A deadline pushed further out leaves yesterday's warning standing and wrong, and a key that
     * moves from due to expired would otherwise be listed twice. Reading the whole table to decide
     * is affordable because it only ever holds one row per expiring key.
     */
    private void withdrawSuperseded(Instant now, NotificationProperties.Expiry thresholds) {
        var standing = notifications.findAll();
        if (standing.isEmpty()) return;

        var byId = new HashMap<UUID, Credential>();
        credentials
                .findAllWithOwner(standing.stream()
                        .map(Notification::getCredentialId)
                        .distinct()
                        .toList())
                .forEach(credential -> byId.put(credential.getId(), credential));

        var withdrawn = standing.stream()
                .filter(notification -> {
                    var credential = byId.get(notification.getCredentialId());
                    if (credential == null || !credential.isEnabled()) return true;
                    // The date on the announcement is the one it was made about. A different one is a
                    // different promise, and this sentence was written about the old one.
                    if (!Objects.equals(credential.getExpiresAt(), notification.getExpiresAt())) return true;
                    return ExpiryStage.reached(
                                    credential.getExpiresAt(), now, thresholds.noticeDays(), thresholds.warningDays())
                            .filter(reached -> reached == notification.getStage())
                            .isEmpty();
                })
                .toList();

        if (withdrawn.isEmpty()) return;
        notifications.deleteAll(withdrawn);

        // Withdrawing the sentence has to withdraw the claim that produced it, or a secret disabled
        // and switched back on would keep its silence until the next stage arrived.
        var released = withdrawn.stream()
                .map(Notification::getCredentialId)
                .filter(byId::containsKey)
                .distinct()
                .toList();
        if (!released.isEmpty()) credentials.releaseExpiryStages(released);
        log.info("Withdrew {} announcement(s) the current deadlines no longer support", withdrawn.size());
    }

    private String detail(Credential credential, ExpiryStage stage, Instant now) {
        long days = ExpiryStage.daysRemaining(credential.getExpiresAt(), now);
        return stage == ExpiryStage.EXPIRED
                ? "%s expired %d day(s) ago".formatted(credential.getName(), Math.abs(days))
                : "%s expires in %d day(s)".formatted(credential.getName(), days);
    }
}
