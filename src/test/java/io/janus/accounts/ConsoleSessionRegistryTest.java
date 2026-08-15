package io.janus.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Ending the sessions a password change makes stale.
 *
 * <p>What is being defended is the reason somebody changes a password in the first place: if they
 * believe it was learned, whoever learned it may already be signed in, and a change that leaves that
 * session working has closed nothing. The one exception is the session the change is being made on,
 * or the act of changing a password would sign the person out of the console mid-form.
 */
class ConsoleSessionRegistryTest {

    private final ConsoleSessionRegistry registry = new ConsoleSessionRegistry();

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** Puts a request carrying this session behind the calls that follow, as a servlet would. */
    private void requestOn(MockHttpSession session) {
        var request = new MockHttpServletRequest();
        request.setSession(session);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void endsTheOtherSessionsAnAccountHoldsAndKeepsTheOneBeingUsed() {
        var account = UUID.randomUUID();
        var here = new MockHttpSession();
        var elsewhere = new MockHttpSession();
        var laptop = new MockHttpSession();
        registry.opened(account, here);
        registry.opened(account, elsewhere);
        registry.opened(account, laptop);
        requestOn(here);

        assertThat(registry.endOthers(account)).isEqualTo(2);
        assertThat(here.isInvalid()).isFalse();
        assertThat(elsewhere.isInvalid()).isTrue();
        assertThat(laptop.isInvalid()).isTrue();
    }

    /** An administrator resetting somebody else's password spares none of the target's sessions. */
    @Test
    void aResetFromAnotherAccountEndsEveryOneOfTheTargetsSessions() {
        var target = UUID.randomUUID();
        var theirs = new MockHttpSession();
        registry.opened(target, theirs);
        registry.opened(UUID.randomUUID(), new MockHttpSession());
        requestOn(new MockHttpSession());

        assertThat(registry.endOthers(target)).isEqualTo(1);
        assertThat(theirs.isInvalid()).isTrue();
    }

    /** Nothing to end, and no request to read a session from, are both ordinary rather than errors. */
    @Test
    void anAccountWithNothingOpenEndsNothing() {
        assertThat(registry.endOthers(UUID.randomUUID())).isZero();
    }

    /**
     * The container ends sessions on its own, when one times out or somebody signs out. Forgetting
     * them then is what keeps this from holding every session the process ever created.
     */
    @Test
    void aSessionTheContainerEndsIsForgotten() {
        var account = UUID.randomUUID();
        var session = new MockHttpSession();
        registry.opened(account, session);

        registry.sessionDestroyed(new jakarta.servlet.http.HttpSessionEvent(session));

        assertThat(registry.endOthers(account)).isZero();
        assertThat(session.isInvalid()).isFalse();
    }
}
