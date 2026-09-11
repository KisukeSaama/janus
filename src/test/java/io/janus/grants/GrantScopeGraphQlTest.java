package io.janus.grants;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.janus.gateway.graphql.OperationType;

/** The two parts of a grant that speak GraphQL: which kinds of operation, and which root fields. */
class GrantScopeGraphQlTest {

    @Test
    void namesTheOperationsAndRootFieldsItAdmits() {
        var scope = GrantScope.of(null, null, true, "query", "viewer, repository");

        assertThat(scope.narrowsGraphQl()).isTrue();
        assertThat(scope.narrows()).isTrue();
        assertThat(scope.admitsOperation(OperationType.QUERY)).isTrue();
        assertThat(scope.admitsOperation(OperationType.MUTATION)).isFalse();
        assertThat(scope.admitsRootField("viewer")).isTrue();
        assertThat(scope.admitsRootField("organization")).isFalse();
    }

    /** Clients add it on their own, and it reaches nothing. */
    @Test
    void alwaysAdmitsTypename() {
        assertThat(GrantScope.of(null, null, true, null, "viewer").admitsRootField("__typename"))
                .isTrue();
    }

    @Test
    void readsTheSameWayWhateverOrderItWasWrittenIn() {
        var scope = GrantScope.of(null, null, true, "SUBSCRIPTION,QUERY", "viewer,repository");

        assertThat(scope.storedOperations()).isEqualTo("QUERY,SUBSCRIPTION");
        assertThat(scope.storedRootFields()).isEqualTo("repository,viewer");
        var reread = GrantScope.of(null, null, true, scope.storedOperations(), scope.storedRootFields());
        assertThat(reread).isEqualTo(scope);
    }

    /** GraphQL names are case-sensitive; `Viewer` is a different field. */
    @Test
    void keepsTheCaseOfARootField() {
        assertThat(GrantScope.of(null, null, true, null, "viewer").admitsRootField("Viewer"))
                .isFalse();
    }

    @Test
    void refusesWhatCouldNeverMatch() {
        assertThatThrownBy(() -> GrantScope.of(null, null, true, "DELETE", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        GrantScope.of(null, null, true, null, "not-a-name").validate())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nothingStatedIsStillTheWholeDestination() {
        assertThat(GrantScope.of(null, null, true, null, null)).isSameAs(GrantScope.EVERYTHING);
        assertThat(GrantScope.EVERYTHING.narrowsGraphQl()).isFalse();
        assertThat(GrantScope.EVERYTHING.admitsOperation(OperationType.MUTATION))
                .isTrue();
    }
}
