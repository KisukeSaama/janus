package io.janus.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;

import org.junit.jupiter.api.*;

import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.credentials.*;
import io.janus.providers.Provider;

class CredentialExpiryMonitorTest {
    private static final Instant NOW = Instant.parse("2026-08-10T09:00:00Z");

    private final CredentialRepository credentials = mock(CredentialRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final ExpiryMailer mailer = mock(ExpiryMailer.class);
    private final AuditService audit = mock(AuditService.class);
    private final CredentialExpiryMonitor monitor = new CredentialExpiryMonitor(
            credentials,
            notifications,
            mailer,
            audit,
            new NotificationProperties(
                    "0 15 7 * * *",
                    "UTC",
                    new NotificationProperties.Expiry(30, 7),
                    new NotificationProperties.Email(false, List.of(), "janus@localhost", "[Janus]")));

    @BeforeEach
    void setUp() {
        when(notifications.save(any(Notification.class))).thenAnswer(call -> call.getArgument(0));
        when(notifications.findAll()).thenReturn(List.of());
        when(notifications.findByCredentialIdAndStage(any(), any())).thenReturn(Optional.empty());
        when(credentials.claimExpiryStage(any(), any())).thenReturn(1);
        when(credentials.findExpiringBy(any())).thenReturn(List.of());
        when(credentials.findAllWithOwner(any())).thenReturn(List.of());
    }

    private static Credential expiringIn(long days) {
        var provider = new Provider(
                io.janus.accounts.TestAccount.owner(),
                "Payments",
                "payments",
                "https://api.example.com",
                true,
                new Provider.TrafficPolicy(true, 0, 0, 0));
        return new Credential(
                provider,
                "payments-live",
                Credential.Strategy.of(AuthType.BEARER),
                NOW.plus(Duration.ofDays(days)),
                true);
    }

    private static void reschedule(Credential credential, Instant expiresAt) {
        credential.describe(
                credential.getName(),
                Credential.Strategy.of(credential.getAuthType()),
                expiresAt,
                credential.isEnabled());
    }

    private static void disable(Credential credential) {
        credential.describe(
                credential.getName(),
                Credential.Strategy.of(credential.getAuthType()),
                credential.getExpiresAt(),
                false);
    }

    @Test
    void announcesTheStageTheDeadlineHasReached() {
        var credential = expiringIn(5);
        when(credentials.findExpiringBy(any())).thenReturn(List.of(credential));

        var raised = monitor.announce(NOW);

        assertThat(raised).singleElement().satisfies(notification -> {
            assertThat(notification.getStage()).isEqualTo(ExpiryStage.WARNING);
            assertThat(notification.getCredentialName()).isEqualTo("payments-live");
            assertThat(notification.getProviderName()).isEqualTo("Payments");
        });
        verify(audit)
                .recordSystem(
                        eq(AuditAction.CREDENTIAL_EXPIRY_WARNING),
                        eq(credential.getProvider().getId()),
                        anyString());
    }

    /** The claim is the whole idempotency story: a second sweep moves no row, so it says nothing. */
    @Test
    void saysNothingWhenTheStageIsAlreadyClaimed() {
        when(credentials.findExpiringBy(any())).thenReturn(List.of(expiringIn(5)));
        when(credentials.claimExpiryStage(any(), any())).thenReturn(0);

        assertThat(monitor.announce(NOW)).isEmpty();
        verify(notifications, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void aDeadlineBeyondTheNoticeWindowIsNotAnnouncedEvenIfItIsRead() {
        when(credentials.findExpiringBy(any())).thenReturn(List.of(expiringIn(45)));

        assertThat(monitor.announce(NOW)).isEmpty();
        verify(credentials, never()).claimExpiryStage(any(), any());
    }

    @Test
    void restatesAnExistingRowRatherThanRaisingASecondOne() {
        var credential = expiringIn(5);
        var standing = Notification.of(credential, ExpiryStage.WARNING, NOW.minus(Duration.ofDays(40)));
        when(credentials.findExpiringBy(any())).thenReturn(List.of(credential));
        when(notifications.findByCredentialIdAndStage(credential.getId(), ExpiryStage.WARNING))
                .thenReturn(Optional.of(standing));

        var raised = monitor.announce(NOW);

        assertThat(raised).containsExactly(standing);
        assertThat(standing.getCreatedAt()).isEqualTo(NOW);
        assertThat(standing.getReadAt()).isNull();
    }

    @Test
    void withdrawsAnAnnouncementThePostponedDeadlineNoLongerSupports() {
        var credential = expiringIn(5);
        var standing = Notification.of(credential, ExpiryStage.WARNING, NOW);
        reschedule(credential, NOW.plus(Duration.ofDays(120)));
        when(notifications.findAll()).thenReturn(List.of(standing));
        when(credentials.findAllWithOwner(any())).thenReturn(List.of(credential));

        monitor.announce(NOW);

        verify(notifications).deleteAll(List.of(standing));
        verify(credentials).releaseExpiryStages(List.of(credential.getId()));
    }

    @Test
    void replacesTheEarlierStageWhenAWorseOneArrives() {
        var credential = expiringIn(3);
        var standing = Notification.of(credential, ExpiryStage.NOTICE, NOW.minus(Duration.ofDays(25)));
        when(notifications.findAll()).thenReturn(List.of(standing));
        when(credentials.findAllWithOwner(any())).thenReturn(List.of(credential));
        when(credentials.findExpiringBy(any())).thenReturn(List.of(credential));

        var raised = monitor.announce(NOW);

        verify(notifications).deleteAll(List.of(standing));
        assertThat(raised).singleElement().extracting(Notification::getStage).isEqualTo(ExpiryStage.WARNING);
    }

    @Test
    void leavesAStandingAnnouncementAloneWhileItIsStillTrue() {
        var credential = expiringIn(3);
        var standing = Notification.of(credential, ExpiryStage.WARNING, NOW.minus(Duration.ofDays(1)));
        when(notifications.findAll()).thenReturn(List.of(standing));
        when(credentials.findAllWithOwner(any())).thenReturn(List.of(credential));

        monitor.announce(NOW);

        verify(notifications, never()).deleteAll(any());
        verify(credentials, never()).releaseExpiryStages(any());
    }

    /** A disabled secret authorizes nothing, so its deadline is nobody's work until it is switched on. */
    @Test
    void withdrawsTheAnnouncementOfADisabledSecretAndGivesItsStageBack() {
        var credential = expiringIn(3);
        var standing = Notification.of(credential, ExpiryStage.WARNING, NOW);
        disable(credential);
        when(notifications.findAll()).thenReturn(List.of(standing));
        when(credentials.findAllWithOwner(any())).thenReturn(List.of(credential));

        monitor.announce(NOW);

        verify(notifications).deleteAll(List.of(standing));
        verify(credentials).releaseExpiryStages(List.of(credential.getId()));
    }

    @Test
    void withdrawsAnAnnouncementWhoseSecretIsGoneWithoutTouchingTheMissingRow() {
        var standing = Notification.of(expiringIn(3), ExpiryStage.WARNING, NOW);
        when(notifications.findAll()).thenReturn(List.of(standing));
        when(credentials.findAllWithOwner(any())).thenReturn(List.of());

        monitor.announce(NOW);

        verify(notifications).deleteAll(List.of(standing));
        verify(credentials, never()).releaseExpiryStages(any());
    }
}
