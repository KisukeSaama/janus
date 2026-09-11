package io.janus.gateway.graphql;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import io.janus.grants.GrantScope;
import io.janus.shared.ErrorCode;

/** The small rules the GraphQL path is built from: where the endpoint is, and what an answer says. */
class GraphQlSupportTest {

    // --- the endpoint ---------------------------------------------------------

    /** As generous as the servers GraphQL runs on, or a caller steps around the read with /GraphQL/. */
    @Test
    void matchesTheEndpointWhateverItsCaseAndTrailingSlash() {
        assertThat(GraphQlEndpoint.matches("/graphql", "/graphql")).isTrue();
        assertThat(GraphQlEndpoint.matches("/graphql", "/GraphQL/")).isTrue();
        assertThat(GraphQlEndpoint.matches("/graphql", "/graphql/extra")).isFalse();
        assertThat(GraphQlEndpoint.matches(null, "/graphql")).isFalse();
    }

    @Test
    void normalisesADeclaredPathAndRefusesOneThatCouldBeReadTwoWays() {
        assertThat(GraphQlEndpoint.normalise("graphql/")).isEqualTo("/graphql");
        assertThat(GraphQlEndpoint.normalise("/")).isEqualTo("/");
        assertThat(GraphQlEndpoint.normalise("  ")).isNull();
        assertThatThrownBy(() -> GraphQlEndpoint.normalise("/v1/../graphql"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GraphQlEndpoint.normalise("/graphql?x=1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- what an answer says --------------------------------------------------

    private static HttpHeaders json() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static GraphQlResponses.Summary read(String body) {
        return GraphQlResponses.read(json(), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void countsTheErrorsA200Carries() {
        var summary = read("{\"data\":{\"a\":1},\"errors\":[{\"message\":\"x\"},{\"message\":\"y\"}]}");

        assertThat(summary.errors()).isEqualTo(2);
        assertThat(summary.refused()).isFalse();
    }

    /** No data and nothing but refusals is how a GraphQL server says 401. */
    @Test
    void readsARefusalWrittenIntoTheBody() {
        var summary =
                read("{\"data\":null,\"errors\":[{\"message\":\"no\",\"extensions\":{\"code\":\"UNAUTHENTICATED\"}}]}");

        assertThat(summary.refused()).isTrue();
    }

    @Test
    void doesNotCallAPartialAnswerARefusal() {
        var summary =
                read("{\"data\":{\"a\":1},\"errors\":[{\"message\":\"no\",\"extensions\":{\"code\":\"FORBIDDEN\"}}]}");

        assertThat(summary.refused()).isFalse();
    }

    @Test
    void leavesAnAnswerThatIsNotJsonUnread() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);

        assertThat(GraphQlResponses.read(headers, "<html>errors</html>".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(GraphQlResponses.Summary.CLEAN);
    }

    @Test
    void sumsTheErrorsOfABatch() {
        assertThat(read("[{\"data\":{},\"errors\":[{}]},{\"data\":{},\"errors\":[{},{}]}]")
                        .errors())
                .isEqualTo(3);
    }

    // --- the grant's ceiling --------------------------------------------------

    private static GraphQlCall call(OperationType type, String... fields) {
        return GraphQlCall.of(new GraphQlOperation(type, null, Set.of(fields), 1, 0));
    }

    @Test
    void aReadOnlyGrantAdmitsQueriesAndRefusesMutations() {
        var scope = GrantScope.of(null, null, true, "QUERY", null);

        GraphQlScope.check(scope, call(OperationType.QUERY, "viewer"));
        var refused = catchThrowableOfType(
                GraphQlRefusal.class, () -> GraphQlScope.check(scope, call(OperationType.MUTATION, "wipe")));

        assertThat(refused.code()).isEqualTo(ErrorCode.GRAPHQL_OPERATION_NOT_GRANTED);
    }

    @Test
    void aGrantNamingRootFieldsRefusesTheOthers() {
        var scope = GrantScope.of(null, null, true, null, "viewer,repository");

        GraphQlScope.check(scope, call(OperationType.QUERY, "viewer"));
        var refused = catchThrowableOfType(
                GraphQlRefusal.class,
                () -> GraphQlScope.check(scope, call(OperationType.QUERY, "viewer", "organization")));

        assertThat(refused.code()).isEqualTo(ErrorCode.GRAPHQL_FIELD_NOT_GRANTED);
    }

    /** Nothing to check the ceiling against, so nothing is admitted under it. */
    @Test
    void aNarrowedGrantRefusesAnOperationItCouldNotRead() {
        var scope = GrantScope.of(null, null, true, "QUERY", null);

        assertThatThrownBy(() -> GraphQlScope.check(scope, GraphQlCall.unread()))
                .isInstanceOf(GraphQlRefusal.class);
    }

    @Test
    void aGrantThatSaysNothingAboutGraphQlAdmitsEverything() {
        GraphQlScope.check(GrantScope.EVERYTHING, call(OperationType.MUTATION, "wipe"));
        GraphQlScope.check(GrantScope.EVERYTHING, GraphQlCall.unread());
    }
}
