package io.janus.grants;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The ceiling a grant may put on itself, for the credentials no upstream can narrow.
 *
 * <p>Two things are being defended. That saying nothing goes on meaning the whole destination, so no
 * existing grant changes meaning; and that a prefix is compared as a path rather than as text, which
 * is the mistake that would quietly admit {@code /v1/users} to a grant naming {@code /v1/user}.
 */
class GrantScopeTest {

    @Test
    void aGrantThatSaysNothingAdmitsTheWholeDestination() {
        var scope = GrantScope.of(null, null, true);

        assertThat(scope.narrows()).isFalse();
        assertThat(scope.admitsPath("/v1/anything/at/all")).isTrue();
        assertThat(scope.admitsMethod("DELETE")).isTrue();
    }

    @Test
    void aPrefixAdmitsItselfAndWhatIsUnderIt() {
        var scope = GrantScope.of("/library/sections", null, true);

        assertThat(scope.admitsPath("/library/sections")).isTrue();
        assertThat(scope.admitsPath("/library/sections/3/all")).isTrue();
    }

    /** The whole point of comparing segments: a longer name is not the same resource. */
    @Test
    void aPrefixDoesNotAdmitAPathThatMerelyStartsWithItsLetters() {
        var scope = GrantScope.of("/v1/user", null, true);

        assertThat(scope.admitsPath("/v1/users")).isFalse();
        assertThat(scope.admitsPath("/v1/user-settings")).isFalse();
        assertThat(scope.admitsPath("/v1/user/settings")).isTrue();
    }

    @Test
    void aPathOutsideThePrefixIsRefused() {
        var scope = GrantScope.of("/library", null, true);

        assertThat(scope.admitsPath("/status/sessions")).isFalse();
        assertThat(scope.admitsPath("/")).isFalse();
    }

    /** "/" is every path, so storing it would be a narrowing that narrows nothing. */
    @Test
    void theRootIsTheSameAsHavingSaidNothing() {
        var scope = GrantScope.of("/", null, true);

        assertThat(scope.narrows()).isFalse();
        assertThat(scope.storedPrefix()).isNull();
    }

    @Test
    void aTrailingSlashIsNotADistinctionAnybodyMeant() {
        assertThat(GrantScope.of("/library/sections/", null, true).storedPrefix())
                .isEqualTo("/library/sections");
    }

    @Test
    void aPrefixWrittenWithoutItsLeadingSlashIsReadAsAPath() {
        assertThat(GrantScope.of("library", null, true).storedPrefix()).isEqualTo("/library");
    }

    @Test
    void aPrefixThatCouldBeReadTwoWaysIsRefusedWhereItIsWritten() {
        assertThatThrownBy(() -> GrantScope.of("/library/../admin", null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrantScope.of("/library//sections", null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrantScope.of("/library?token=x", null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GrantScope.of("/library#top", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namedMethodsAdmitThemselvesAndNothingElse() {
        var scope = GrantScope.of(null, "GET,HEAD", true);

        assertThat(scope.narrows()).isTrue();
        assertThat(scope.admitsMethod("GET")).isTrue();
        assertThat(scope.admitsMethod("get")).isTrue();
        assertThat(scope.admitsMethod("DELETE")).isFalse();
    }

    @Test
    void methodsAreStoredAndReturnedInOneOrderHoweverTheyWereWritten() {
        var scope = GrantScope.of(null, "delete, get", true);

        assertThat(scope.storedMethods()).isEqualTo("GET,DELETE");
        assertThat(scope.orderedMethods()).containsExactly("GET", "DELETE");
    }

    /** A method the gateway would never forward is a typo, and it is reported where it was typed. */
    @Test
    void aMethodTheGatewayDoesNotForwardIsRefused() {
        assertThatThrownBy(() -> new GrantScope(null, Set.of("TRACE"), true).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRACE");
    }

    /** What was written is what comes back, so the console shows the grant it saved. */
    @Test
    void whatIsStoredIsWhatIsReadBack() {
        var stored = GrantScope.of("/library/sections", "GET,HEAD", true);
        var reread = GrantScope.of(stored.storedPrefix(), stored.storedMethods(), stored.admitsAccountIdentity());

        assertThat(reread).isEqualTo(stored);
        assertThat(reread.orderedMethods()).isEqualTo(List.of("GET", "HEAD"));
    }

    /**
     * Whom a call may be made as is the third thing a grant may narrow, and the one that is not
     * about the destination's surface at all. Admitted by default, because that is what every grant
     * written before the question was asked already does.
     */
    @Test
    void theAccountIdentityIsAdmittedUnlessAGrantSaysOtherwise() {
        assertThat(GrantScope.EVERYTHING.admitsAccountIdentity()).isTrue();
        assertThat(GrantScope.of(null, null, true)).isEqualTo(GrantScope.EVERYTHING);

        var appOnly = GrantScope.of(null, null, false);

        assertThat(appOnly.admitsAccountIdentity()).isFalse();
        // It narrows nothing about the surface and is still a narrowing, which is what the console
        // reads to decide whether this grant has anything to say beyond "the whole destination".
        assertThat(appOnly.narrows()).isTrue();
        assertThat(appOnly.admitsPath("/v1/anything")).isTrue();
        assertThat(appOnly.admitsMethod("DELETE")).isTrue();
    }
}
