package io.janus.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.janus.credentials.*;
import io.janus.providers.Provider;
import io.janus.shared.ApiExceptionHandler;

class NotificationAdminControllerTest {
    private static final Instant NOW = Instant.parse("2026-08-10T09:00:00Z");

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final io.janus.accounts.AccessScope scope = mock(io.janus.accounts.AccessScope.class);
    /** The one owner these announcements are about, and the one the caller is. */
    private static final io.janus.accounts.Account OWNER = io.janus.accounts.TestAccount.owner();

    private final UUID owner = OWNER.getId();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(scope.ownerFilter()).thenReturn(owner);
        var controller = new NotificationAdminController(new NotificationService(repository, scope));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Notification announcement(ExpiryStage stage) {
        var provider = new Provider(
                OWNER,
                "Payments",
                "payments",
                "https://api.example.com",
                true,
                new Provider.TrafficPolicy(true, 0, 0, 0));
        var credential = new Credential(
                provider, "payments-live", Credential.Strategy.of(AuthType.BEARER), NOW.plus(Duration.ofDays(4)), true);
        var notification = Notification.of(credential, stage, NOW);
        notification.onCreate();
        return notification;
    }

    @Test
    void theFeedCarriesTheStageItsToneAndTheUnreadCount() throws Exception {
        when(repository.findByOwnerIdOrderByCreatedAtDesc(owner))
                .thenReturn(List.of(announcement(ExpiryStage.WARNING)));
        when(repository.countByOwnerIdAndReadAtIsNull(owner)).thenReturn(1L);

        mvc.perform(get("/api/admin/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(1))
                .andExpect(jsonPath("$.items[0].stage").value("WARNING"))
                .andExpect(jsonPath("$.items[0].severity").value("WARN"))
                .andExpect(jsonPath("$.items[0].credentialName").value("payments-live"))
                .andExpect(jsonPath("$.items[0].providerName").value("Payments"));
    }

    /** How many days are left is derived where it is shown, so a page left open cannot go stale. */
    @Test
    void theFeedStatesTheDeadlineRatherThanACountdown() throws Exception {
        when(repository.findByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(announcement(ExpiryStage.NOTICE)));

        mvc.perform(get("/api/admin/notifications"))
                .andExpect(jsonPath("$.items[0].expiresAt").exists())
                .andExpect(jsonPath("$.items[0].daysRemaining").doesNotExist());
    }

    @Test
    void unreadOnlyReadsTheNarrowerQuery() throws Exception {
        when(repository.findByOwnerIdAndReadAtIsNullOrderByCreatedAtDesc(owner)).thenReturn(List.of());

        mvc.perform(get("/api/admin/notifications").param("unreadOnly", "true")).andExpect(status().isOk());

        verify(repository).findByOwnerIdAndReadAtIsNullOrderByCreatedAtDesc(owner);
        verify(repository, never()).findByOwnerIdOrderByCreatedAtDesc(any());
    }

    @Test
    void readingOneStampsItOnce() throws Exception {
        var notification = announcement(ExpiryStage.EXPIRED);
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));

        mvc.perform(post("/api/admin/notifications/" + notification.getId() + "/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").exists());

        var first = notification.getReadAt();
        notification.markRead(NOW.plus(Duration.ofDays(1)));
        assertThat(notification.getReadAt()).isEqualTo(first);
    }

    @Test
    void readingAnUnknownAnnouncementIsNotFound() throws Exception {
        var id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        mvc.perform(post("/api/admin/notifications/" + id + "/read")).andExpect(status().isNotFound());
    }

    /**
     * An announcement about somebody else's secret is not a forbidden one; from where this caller
     * stands it does not exist, and acting on it changes nothing.
     */
    @Test
    void anAnnouncementAboutSomebodyElsesSecretReadsAsNotFound() throws Exception {
        var stranger = new Provider(
                io.janus.accounts.TestAccount.owner("stranger"),
                "Their API",
                "theirs",
                "https://api.example.com",
                true,
                new Provider.TrafficPolicy(true, 0, 0, 0));
        var theirs = Notification.of(
                new Credential(
                        stranger,
                        "their-key",
                        Credential.Strategy.of(AuthType.BEARER),
                        NOW.plus(Duration.ofDays(2)),
                        true),
                ExpiryStage.WARNING,
                NOW);
        theirs.onCreate();
        when(repository.findById(theirs.getId())).thenReturn(Optional.of(theirs));

        mvc.perform(post("/api/admin/notifications/" + theirs.getId() + "/read"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/notifications/" + theirs.getId())).andExpect(status().isNotFound());
        verify(repository, never()).delete(any());
    }

    @Test
    void anUnusableIdentifierIsAClientErrorRatherThanAServerError() throws Exception {
        mvc.perform(delete("/api/admin/notifications/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void dismissingRemovesTheAnnouncement() throws Exception {
        var notification = announcement(ExpiryStage.NOTICE);
        when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));

        mvc.perform(delete("/api/admin/notifications/" + notification.getId())).andExpect(status().isOk());

        verify(repository).delete(notification);
    }

    @Test
    void markingEverythingReadStampsTheWholeTableAndAnswersTheFreshFeed() throws Exception {
        when(repository.findByOwnerIdOrderByCreatedAtDesc(owner)).thenReturn(List.of());
        when(repository.countByOwnerIdAndReadAtIsNull(owner)).thenReturn(0L);

        mvc.perform(post("/api/admin/notifications/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));

        verify(repository).markAllRead(any(), any());
    }
}
