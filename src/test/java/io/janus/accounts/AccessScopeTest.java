package io.janus.accounts;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AccessScopeTest {
    private final AccessScope scope = new AccessScope();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static ConsoleUser signIn(AccountRole role) {
        var account = new Account("someone", "Someone", "someone@example.com", "hash", role, true);
        var user = new ConsoleUser(account);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        return user;
    }

    /**
     * The rule the separation rests on: no role widens what registry somebody sees. An administrator
     * manages who may sign in, not what other people registered.
     */
    @Test
    void everyRoleIsFilteredToItsOwnRegistryIncludingASuperAdministrator() {
        for (var role : AccountRole.values()) {
            var user = signIn(role);
            assertThat(scope.ownerFilter()).as("registry scope of %s", role).isEqualTo(user.id());
        }
    }

    /** Administration is about accounts. It is not a way into somebody else's registry. */
    @Test
    void administeringAccountsDoesNotWidenWhatIsVisible() {
        assertThat(AccountRole.SUPER_ADMIN.administers()).isTrue();
        assertThat(AccountRole.ADMIN.administers()).isTrue();
        assertThat(AccountRole.USER.administers()).isFalse();

        var admin = signIn(AccountRole.ADMIN);
        assertThat(scope.ownerFilter()).isEqualTo(admin.id());
    }

    /**
     * The one that matters. An unauthenticated caller reaching a scoped query is a bug, and a scope
     * that answered with something rather than refusing would decide what it cannot know.
     */
    @Test
    void anUnauthenticatedContextIsRefusedRatherThanAnswered() {
        assertThatThrownBy(scope::ownerFilter).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(scope::accountId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(scope::role).isInstanceOf(IllegalStateException.class);
        assertThat(scope.signedIn()).isEmpty();
    }

    /** The gateway authenticates applications, not people; its principal must not pass for one. */
    @Test
    void aPrincipalThatIsNotAConsoleUserIsNotSignedIn() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated("orders", null, java.util.List.of()));
        assertThat(scope.signedIn()).isEmpty();
        assertThatThrownBy(scope::ownerFilter).isInstanceOf(IllegalStateException.class);
    }
}
