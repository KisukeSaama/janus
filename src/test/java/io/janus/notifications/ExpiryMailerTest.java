package io.janus.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import io.janus.accounts.*;
import io.janus.credentials.*;
import io.janus.providers.Provider;

class ExpiryMailerTest {
    private static final Instant NOW = Instant.parse("2026-08-10T09:00:00Z");

    private final JavaMailSender sender = Mockito.mock(JavaMailSender.class);
    private final AccountRepository accounts = Mockito.mock(AccountRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<JavaMailSender> senders = Mockito.mock(ObjectProvider.class);

    private final Account ada = TestAccount.owner("ada");
    private final Account bo = TestAccount.owner("bo");

    @BeforeEach
    void setUp() {
        when(senders.getIfAvailable()).thenReturn(sender);
        when(accounts.findAllById(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return Stream.of(ada, bo).filter(a -> ids.contains(a.getId())).toList();
        });
    }

    private ExpiryMailer mailer(String... globalRecipients) {
        var properties = new NotificationProperties(
                "0 15 7 * * *",
                "UTC",
                new NotificationProperties.Expiry(30, 7),
                new NotificationProperties.Email(true, List.of(globalRecipients), "janus@localhost", "[Janus]"));
        return new ExpiryMailer(senders, accounts, properties);
    }

    private static Notification announcement(Account owner, String secretName, long days) {
        var provider = new Provider(
                owner,
                "Payments",
                "payments",
                "https://api.example.com",
                true,
                new Provider.TrafficPolicy(true, 0, 0, 0));
        var credential = new Credential(
                provider, secretName, Credential.Strategy.of(AuthType.BEARER), NOW.plus(Duration.ofDays(days)), true);
        return Notification.of(credential, ExpiryStage.WARNING, NOW);
    }

    private List<SimpleMailMessage> sent() {
        var captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    /** The whole point of the lot: the person who has to rotate the key is the one who is told. */
    @Test
    void sendsOneMessagePerOwnerRatherThanOneForEverybody() {
        mailer().send(List.of(announcement(ada, "ada-key", 5), announcement(bo, "bo-key", 3)));

        var messages = sent();
        assertThat(messages).hasSize(2);
        assertThat(messages.stream().map(m -> m.getTo()[0])).containsExactlyInAnyOrder(ada.getEmail(), bo.getEmail());
    }

    @Test
    void neverTellsOneOwnerAboutAnothersSecrets() {
        mailer().send(List.of(announcement(ada, "ada-key", 5), announcement(bo, "bo-key", 3)));

        for (var message : sent()) {
            boolean toAda = message.getTo()[0].equals(ada.getEmail());
            assertThat(message.getText()).contains(toAda ? "ada-key" : "bo-key");
            assertThat(message.getText()).doesNotContain(toAda ? "bo-key" : "ada-key");
        }
    }

    /** A deployment usually wants one address that hears everything; they are the only ones who do. */
    @Test
    void stillCopiesTheGlobalRecipientsWhenAnyAreConfigured() {
        mailer("ops@example.com").send(List.of(announcement(ada, "ada-key", 5), announcement(bo, "bo-key", 3)));

        var toOps = sent().stream()
                .filter(m -> m.getTo()[0].equals("ops@example.com"))
                .toList();
        assertThat(toOps).hasSize(1);
        assertThat(toOps.getFirst().getText()).contains("ada-key").contains("bo-key");
    }

    @Test
    void sendsNothingWhenNobodyIsConfiguredToBeTold() {
        var properties = new NotificationProperties(
                "0 15 7 * * *",
                "UTC",
                new NotificationProperties.Expiry(30, 7),
                new NotificationProperties.Email(false, List.of(), "janus@localhost", "[Janus]"));
        new ExpiryMailer(senders, accounts, properties).send(List.of(announcement(ada, "ada-key", 5)));

        verifyNoInteractions(sender);
    }

    /** A relay that refuses one address must not cost the others theirs. */
    @Test
    void aRefusedMessageDoesNotStopTheRest() {
        doThrow(new MailSendException("refused")).doNothing().when(sender).send(any(SimpleMailMessage.class));

        assertThatNoException()
                .isThrownBy(
                        () -> mailer().send(List.of(announcement(ada, "ada-key", 5), announcement(bo, "bo-key", 3))));
        verify(sender, times(2)).send(any(SimpleMailMessage.class));
    }

    /** A disabled account cannot sign in to act on it; the announcement stays in the console. */
    @Test
    void aDisabledOwnerIsNotWritten() {
        bo.describe(bo.getDisplayName(), bo.getEmail(), false);

        mailer().send(List.of(announcement(ada, "ada-key", 5), announcement(bo, "bo-key", 3)));

        assertThat(sent()).hasSize(1);
        assertThat(sent().getFirst().getTo()[0]).isEqualTo(ada.getEmail());
    }

    /** The subject states the worst of it: that is what decides whether the mail is opened now. */
    @Test
    void theSubjectNamesWhatIsDue() {
        mailer().send(List.of(announcement(ada, "ada-key", 5)));

        assertThat(sent().getFirst().getSubject()).startsWith("[Janus]").contains("ada-key");
    }

    /**
     * A subject is one header line, and the name in it was typed by whoever registered the secret.
     * A newline there would let them append headers of their own to a message the platform's address
     * receives. The request validators refuse one; this covers the rows written before they did.
     */
    @Test
    void aNameCarryingANewlineCannotAppendHeadersToTheSubject() {
        mailer().send(List.of(announcement(ada, "ada-key\r\nBcc: elsewhere@example.com", 5)));

        String subject = sent().getFirst().getSubject();
        assertThat(subject).doesNotContain("\r").doesNotContain("\n");
        assertThat(subject).startsWith("[Janus]");
    }
}
