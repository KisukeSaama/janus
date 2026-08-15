package io.janus.accounts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.context.SecurityContextHolder;

import io.janus.audit.AuditAction;
import io.janus.audit.AuditService;
import io.janus.security.AuthenticationThrottle;

class SessionServiceTest {
    private final AuthenticationManager authentication = Mockito.mock(AuthenticationManager.class);
    private final AccountRepository accounts = Mockito.mock(AccountRepository.class);
    private final AuditService audit = Mockito.mock(AuditService.class);
    private final AccessScope scope = new AccessScope();

    private AuthenticationThrottle throttle;
    private SessionService sessions;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        throttle = new AuthenticationThrottle(3, 300, 900);
        sessions = new SessionService(authentication, accounts, scope, throttle, new ConsoleSessionRegistry(), audit);
        request = new MockHttpServletRequest("POST", "/api/admin/session");
        request.setRemoteAddr("203.0.113.10");
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Restubbed with {@code doReturn} rather than {@code when}: a test that first makes the manager
     * throw would otherwise trigger that throw while arranging the next case.
     */
    private ConsoleUser accepts(AccountRole role) {
        var user = new ConsoleUser(new Account("ada", "Ada", "ada@example.com", "hash", role, true));
        doReturn(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()))
                .when(authentication)
                .authenticate(any());
        return user;
    }

    private void refuses() {
        doThrow(new BadCredentialsException("no")).when(authentication).authenticate(any());
    }

    @Test
    void anAcceptedSignInAnswersWithTheIdentityAndRecordsIt() {
        var user = accepts(AccountRole.USER);

        var identity = sessions.signIn("ada", "correct", request, response);

        assertThat(identity.id()).isEqualTo(user.id());
        assertThat(identity.username()).isEqualTo("ada");
        assertThat(identity.role()).isEqualTo(AccountRole.USER);
        verify(accounts).markSignedIn(eq(user.id()), any());
        verify(audit).recordAdmin(eq(AuditAction.ACCOUNT_SIGNED_IN), any(), eq("ada"));
    }

    /**
     * Since Spring Security 6 the holder persists nothing on its own. Without the repository write
     * the sign-in appears to work and the very next request is a 401.
     */
    @Test
    void theContextIsWrittenToTheSessionAndNotOnlyToTheHolder() {
        accepts(AccountRole.ADMIN);

        sessions.signIn("ada", "correct", request, response);

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute("SPRING_SECURITY_CONTEXT"))
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    /** Session fixation: whatever session an attacker planted must not be the authenticated one. */
    @Test
    void theSessionIdentifierChangesAcrossASignIn() {
        accepts(AccountRole.USER);
        request.getSession(true);
        String before = request.getSession(false).getId();

        sessions.signIn("ada", "correct", request, response);

        assertThat(request.getSession(false).getId()).isNotEqualTo(before);
    }

    @Test
    void aRefusedSignInIsCountedAndAudited() {
        refuses();

        assertThatThrownBy(() -> sessions.signIn("ada", "wrong", request, response))
                .isInstanceOf(BadCredentialsException.class);
        verify(audit)
                .recordAuthenticationDenied(any(), eq(AuditAction.ADMIN_AUTHENTICATION), any(), any(), eq(401), any());
        verify(accounts, never()).markSignedIn(any(), any());
    }

    /** Once blocked, the password is not even looked at: the refusal costs no comparison. */
    @Test
    void aBlockedClientIsRefusedWithoutTheCredentialsBeingChecked() {
        refuses();
        for (int attempt = 0; attempt < 3; attempt++)
            assertThatThrownBy(() -> sessions.signIn("ada", "wrong", request, response))
                    .isInstanceOf(BadCredentialsException.class);
        clearInvocations(authentication);

        assertThatThrownBy(() -> sessions.signIn("ada", "wrong", request, response))
                .isInstanceOf(SessionService.TooManyAttemptsException.class);
        verifyNoInteractions(authentication);
    }

    @Test
    void anAcceptedSignInClearsTheFailureCount() {
        refuses();
        assertThatThrownBy(() -> sessions.signIn("ada", "wrong", request, response))
                .isInstanceOf(BadCredentialsException.class);

        accepts(AccountRole.USER);
        sessions.signIn("ada", "correct", request, response);

        // Two more failures would block only if the earlier one still counted.
        refuses();
        for (int attempt = 0; attempt < 2; attempt++)
            assertThatThrownBy(() -> sessions.signIn("ada", "wrong", request, response))
                    .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> sessions.signIn("ada", "wrong", request, response))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void signingOutEndsTheSessionOnTheServer() {
        accepts(AccountRole.USER);
        sessions.signIn("ada", "correct", request, response);
        var session = (MockHttpSession) request.getSession(false);

        sessions.signOut(request);

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(audit).recordAdmin(eq(AuditAction.ACCOUNT_SIGNED_OUT), any(), eq("ada"));
    }
}
