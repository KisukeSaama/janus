package io.janus.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.Executor;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.janus.accounts.*;
import io.janus.shared.CorrelationIdFilter;

/**
 * The operational event stream.
 *
 * <p>What these tests are really about is attribution and survival: an event must name whoever
 * actually acted and nobody else, must fit the columns it is stored in, and must never be the
 * reason a served request fails.
 */
class AuditServiceTest {
    private final AuditEventWriter writer = Mockito.mock(AuditEventWriter.class);
    private final AccessScope scope = Mockito.mock(AccessScope.class);

    /** Runs the hand-off inline, so a test can assert on what was written without waiting. */
    private final Executor inline = Runnable::run;

    private final AuditService audit = new AuditService(writer, inline, scope);

    private final UUID provider = UUID.randomUUID();

    @BeforeEach
    void nobodySignedIn() {
        when(scope.signedIn()).thenReturn(Optional.empty());
    }

    private AuditEvent written() {
        var captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(writer).write(captor.capture());
        return captor.getValue();
    }

    private ConsoleUser signIn(String username) {
        var user = new ConsoleUser(
                new Account(username, "Someone", username + "@example.com", "hash", AccountRole.ADMIN, true));
        when(scope.signedIn()).thenReturn(Optional.of(user));
        return user;
    }

    // --- who is recorded as having acted -------------------------------------

    @Test
    void attributesAnAdministrativeChangeToWhoeverMadeIt() {
        var user = signIn("alice");

        audit.recordAdmin(AuditAction.PROVIDER_CREATED, provider, "Registered spotify");

        var entry = written();
        assertThat(entry.getActorType()).isEqualTo(AuditActor.ADMIN.name());
        assertThat(entry.getActorId()).isEqualTo(user.id().toString());
        assertThat(entry.getActorLabel()).isEqualTo("alice");
        assertThat(entry.getOwnerId()).isEqualTo(user.id());
        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.SUCCESS.name());
    }

    /** Naming an administrator who did not act would be worse than naming nobody. */
    @Test
    void attributesAChangeWithNobodySignedInToJanusItself() {
        audit.recordAdmin(AuditAction.PROVIDER_CREATED, provider, "Startup reconciliation");

        var entry = written();
        assertThat(entry.getActorType()).isEqualTo(AuditActor.SYSTEM.name());
        assertThat(entry.getActorId()).isNull();
        assertThat(entry.getActorLabel()).isNull();
        assertThat(entry.getOwnerId()).isNull();
    }

    @Test
    void recordsWhatJanusDidOnItsOwnScheduleAsSystem() {
        audit.recordSystem(AuditAction.PROVIDER_CREATED, provider, "Nightly sweep");

        assertThat(written().getActorType()).isEqualTo(AuditActor.SYSTEM.name());
    }

    @Test
    void recordsARefusedSignInWithTheSurfaceItWasRefusedOn() {
        audit.recordAuthenticationDenied(
                AuditActor.ADMIN, AuditAction.GATEWAY_REQUEST, "POST", "/api/session", 401, "Bad password");

        var entry = written();
        assertThat(entry.getOutcome()).isEqualTo(AuditOutcome.DENIED.name());
        assertThat(entry.getRequestMethod()).isEqualTo("POST");
        assertThat(entry.getRequestPath()).isEqualTo("/api/session");
        assertThat(entry.getStatusCode()).isEqualTo(401);
    }

    // --- gateway decisions ---------------------------------------------------

    @Test
    void recordsAGatewayDecisionAgainstTheCallingApplicationAndItsOwner() {
        var application = UUID.randomUUID();
        var owner = UUID.randomUUID();

        audit.recordGateway(new AuditService.GatewayEvent(
                application, owner, AuditOutcome.SUCCESS, provider, "GET", "/v1/tracks", 200, "MISS", "corr-1"));

        var entry = written();
        assertThat(entry.getActorType()).isEqualTo(AuditActor.APPLICATION.name());
        assertThat(entry.getActorId()).isEqualTo(application.toString());
        assertThat(entry.getOwnerId()).isEqualTo(owner);
        assertThat(entry.getAction()).isEqualTo(AuditAction.GATEWAY_REQUEST.name());
        assertThat(entry.getCorrelationId()).isEqualTo("corr-1");
        assertThat(entry.getStatusCode()).isEqualTo(200);
    }

    /**
     * The identifier travels on the record rather than in a thread local, precisely because the
     * insert happens on another thread where a thread local would be empty.
     */
    @Test
    void carriesTheCorrelationIdentifierOntoTheThreadThatWritesTheEvent() {
        var executed = new ArrayList<Runnable>();
        var deferred = new AuditService(writer, (Executor) executed::add, scope);

        deferred.recordGateway(new AuditService.GatewayEvent(
                UUID.randomUUID(), null, AuditOutcome.SUCCESS, provider, "GET", "/v1", 200, null, "corr-2"));

        verifyNoInteractions(writer);
        executed.forEach(Runnable::run);
        assertThat(written().getCorrelationId()).isEqualTo("corr-2");
    }

    /** An audit failure must not take a served request down with it. */
    @Test
    void aFailureToRecordAGatewayDecisionDoesNotReachTheCaller() {
        doThrow(new IllegalStateException("audit table is gone")).when(writer).write(any());

        audit.recordGateway(new AuditService.GatewayEvent(
                UUID.randomUUID(), null, AuditOutcome.SUCCESS, provider, "GET", "/v1", 200, null, "corr-3"));

        verify(writer).write(any());
    }

    /** An anonymous gateway call still produces an event; it simply names no application. */
    @Test
    void recordsAGatewayDecisionThatNamedNoApplication() {
        audit.recordGateway(new AuditService.GatewayEvent(
                null, null, AuditOutcome.DENIED, null, "GET", "/v1", 401, "No key", "corr-4"));

        assertThat(written().getActorId()).isNull();
    }

    // --- fitting the columns it is stored in ---------------------------------

    /**
     * A caller controls the path it requests, and the column it lands in is bounded. Truncating is
     * what keeps a long URL from turning an audit insert into a failure on the request path.
     */
    @Test
    void truncatesAPathTooLongForTheColumnItIsStoredIn() {
        String tooLong = "/v1/" + "a".repeat(1000);

        audit.recordGateway(new AuditService.GatewayEvent(
                UUID.randomUUID(), null, AuditOutcome.SUCCESS, provider, "GET", tooLong, 200, null, "corr-5"));

        assertThat(written().getRequestPath()).hasSize(500).startsWith("/v1/aaa");
    }

    @Test
    void truncatesADetailTooLongForTheColumnItIsStoredIn() {
        audit.recordSystem(AuditAction.PROVIDER_CREATED, provider, "x".repeat(1000));

        assertThat(written().getDetail()).hasSize(500);
    }

    @Test
    void leavesShortValuesAloneAndKeepsNullsNull() {
        audit.recordSystem(AuditAction.PROVIDER_CREATED, null, null);

        var entry = written();
        assertThat(entry.getDetail()).isNull();
        assertThat(entry.getProviderId()).isNull();
        assertThat(entry.getCorrelationId()).isNotBlank();
    }

    /** Outside a request there is no identifier to inherit, so one is minted rather than left absent. */
    @Test
    void stampsAnIdentifierOnAnEventRaisedOutsideAnyRequest() {
        assertThat(CorrelationIdFilter.current()).isNotBlank();

        audit.recordSystem(AuditAction.PROVIDER_CREATED, provider, "sweep");

        assertThat(written().getCorrelationId()).isNotBlank();
    }
}
