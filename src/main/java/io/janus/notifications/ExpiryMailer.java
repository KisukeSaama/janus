package io.janus.notifications;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import io.janus.accounts.AccountRepository;
import io.janus.credentials.ExpiryStage;

/**
 * Carries the day's announcements to people who have not opened the console, which is the whole
 * point of an expiry that must not be missed.
 *
 * One message per sweep rather than one per key: a morning that turns up four expiring secrets is
 * one thing to read, not four. Everything here fails quietly — the console already holds every
 * announcement, so a relay that refuses a message must not cost the record of it.
 */
@Component
public class ExpiryMailer {
    private static final Logger log = LoggerFactory.getLogger(ExpiryMailer.class);

    private final ObjectProvider<JavaMailSender> senders;
    private final AccountRepository accounts;
    private final NotificationProperties properties;

    public ExpiryMailer(
            ObjectProvider<JavaMailSender> senders, AccountRepository accounts, NotificationProperties properties) {
        this.senders = senders;
        this.accounts = accounts;
        this.properties = properties;
    }

    /**
     * One message per owner, plus one to whoever is watching the deployment as a whole.
     *
     * <p>Grouping is the point: a secret belongs to somebody, and that somebody is who has to go and
     * rotate the key. Telling everyone about everyone's deadlines is how a notice becomes something
     * people filter. The global recipients are kept as a copy, because a deployment usually wants one
     * address that hears everything — and they are the only ones who see more than their own.
     *
     * <p>Each message is sent on its own. A relay that refuses one address must not cost the others
     * theirs, and none of it may cost the announcements, which are already in the console.
     */
    public void send(List<Notification> raised) {
        var settings = properties.email();
        if (!settings.enabled() || raised.isEmpty()) return;

        // Spring only builds a sender once spring.mail.host is set. Saying so is more useful than
        // an obscure failure on the one morning the message actually mattered.
        var sender = senders.getIfAvailable();
        if (sender == null) {
            log.warn("Expiry mail is enabled but no mail sender is configured; set spring.mail.host to enable it");
            return;
        }

        var now = Instant.now();
        int sent = 0;

        var byOwner = raised.stream().collect(Collectors.groupingBy(Notification::getOwnerId));
        for (var owner : accounts.findAllById(byOwner.keySet())) {
            if (!owner.isEnabled()) continue; // Nobody to read it; the console still holds the row.
            if (deliver(sender, settings, List.of(owner.getEmail()), byOwner.get(owner.getId()), now)) sent++;
        }

        var watchers = settings.recipients().stream()
                .map(String::trim)
                .filter(to -> !to.isEmpty())
                .toList();
        if (!watchers.isEmpty() && deliver(sender, settings, watchers, raised, now)) sent++;

        log.info("Sent {} expiry message(s) covering {} secret(s)", sent, raised.size());
    }

    private boolean deliver(
            JavaMailSender sender,
            NotificationProperties.Email settings,
            List<String> to,
            List<Notification> about,
            Instant now) {
        var message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(to.toArray(String[]::new));
        // A subject is one header line. The names in it are typed by an operator and stored, so a
        // newline reaching here would let whoever named a secret append headers of their own to a
        // message the platform's own address receives. The request validators refuse one; this
        // refuses it again, for the rows written before they did.
        message.setSubject(oneLine("%s %s".formatted(settings.subjectPrefix(), summary(about, now))));
        message.setText(body(about, now));
        try {
            sender.send(message);
            return true;
        } catch (MailException e) {
            // Logged without the address, which is somebody's identity, and without stopping: the
            // next owner's message is not this one's to lose.
            log.error("Could not send an expiry notification; the announcements remain in the console", e);
            return false;
        }
    }

    /** Collapses anything a header cannot carry into a single space, so a subject stays one line. */
    private static String oneLine(String value) {
        var flattened = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            flattened.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return flattened.toString().trim();
    }

    /** The subject states the worst of it, because that is what decides whether the mail is opened now. */
    private String summary(List<Notification> raised, Instant now) {
        var worst = raised.stream()
                .map(Notification::getStage)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        if (raised.size() == 1) {
            var only = raised.getFirst();
            long days = ExpiryStage.daysRemaining(only.getExpiresAt(), now);
            return worst == ExpiryStage.EXPIRED
                    ? "%s has expired".formatted(only.getCredentialName())
                    : "%s expires in %d day(s)".formatted(only.getCredentialName(), days);
        }
        return worst == ExpiryStage.EXPIRED
                ? "%d API keys need attention, some already expired".formatted(raised.size())
                : "%d API keys are approaching expiry".formatted(raised.size());
    }

    private String body(List<Notification> raised, Instant now) {
        var date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(zone());
        var text = new StringBuilder("""
                Janus holds these secrets on your services' behalf, and the dates recorded for them \
                have come due. Rotate the key upstream, then store the new value and its new date \
                in Janus.

                """);
        raised.stream()
                .sorted(Comparator.comparing(Notification::getStage)
                        .reversed()
                        .thenComparing(Notification::getExpiresAt))
                .forEach(item -> {
                    long days = ExpiryStage.daysRemaining(item.getExpiresAt(), now);
                    text.append("  %-8s %s (%s) — %s, %s%n"
                            .formatted(
                                    item.getStage() == ExpiryStage.EXPIRED ? "EXPIRED" : "DUE",
                                    item.getCredentialName(),
                                    item.getProviderName(),
                                    days < 0
                                            ? "expired %d day(s) ago".formatted(-days)
                                            : "expires in %d day(s)".formatted(days),
                                    date.format(item.getExpiresAt())));
                });
        return text.append("\nThis is the only notice sent for each stage.").toString();
    }

    /** A misconfigured zone must not cost the message; the date is still worth reading in UTC. */
    private ZoneId zone() {
        try {
            return ZoneId.of(properties.zone());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }
}
