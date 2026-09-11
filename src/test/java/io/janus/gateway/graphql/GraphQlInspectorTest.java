package io.janus.gateway.graphql;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import io.janus.providers.Provider;
import io.janus.shared.ErrorCode;
import io.janus.testing.Fixtures;

/**
 * Reading a GraphQL request far enough to decide on it. Most of what matters here is refusal: a
 * document that could be read two ways is one a caller would use to send a mutation past a grant
 * that admits only queries.
 */
class GraphQlInspectorTest {
    private final GraphQlProperties properties = GraphQlProperties.defaults();
    private final PersistedQueries persisted = new PersistedQueries(properties);
    private final GraphQlInspector inspector = new GraphQlInspector(new ObjectMapper(), persisted, properties);
    private final Provider provider = graphQl(0, 0);

    private static Provider graphQl(int depth, int aliases) {
        var provider = Fixtures.provider(Fixtures.owner(), "github");
        provider.applyGraphQl(new Provider.GraphQl("/graphql", depth, aliases));
        return provider;
    }

    private static HttpHeaders json() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private GraphQlInspector.Inspection post(Provider provider, String body, boolean enforcing) {
        return inspector.inspect(
                provider, HttpMethod.POST, json(), body.getBytes(StandardCharsets.UTF_8), null, enforcing);
    }

    private GraphQlCall read(String body) {
        return ((GraphQlInspector.Read) post(provider, body, false)).call();
    }

    private static String request(String query) {
        return "{\"query\":" + quote(query) + "}";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static GraphQlRefusal refusal(ThrowingCallable call) {
        var thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(GraphQlRefusal.class);
        return (GraphQlRefusal) thrown;
    }

    // --- what an operation is ------------------------------------------------

    @Test
    void readsTheKindTheRootFieldsAndTheDepthOfAQuery() {
        var call = read(request("query Viewer { viewer { login repositories { totalCount } } }"));

        var operation = call.operations().getFirst();
        assertThat(operation.type()).isEqualTo(OperationType.QUERY);
        assertThat(operation.name()).isEqualTo("Viewer");
        assertThat(operation.rootFields()).containsExactly("viewer");
        assertThat(operation.depth()).isEqualTo(3);
        assertThat(call.readsOnly()).isTrue();
    }

    @Test
    void readsTheShorthandFormAsAQuery() {
        assertThat(read(request("{ viewer { login } }")).readsOnly()).isTrue();
    }

    @Test
    void tellsAMutationFromAQuery() {
        var call = read(request("mutation { deleteRepository(id: 1) { ok } }"));

        assertThat(call.writes()).isTrue();
        assertThat(call.readsOnly()).isFalse();
    }

    @Test
    void resolvesAnAliasToTheFieldItStandsFor() {
        var call = read(request("{ me: viewer { login } }"));

        assertThat(call.operations().getFirst().rootFields()).containsExactly("viewer");
        assertThat(call.operations().getFirst().aliases()).isEqualTo(1);
    }

    @Test
    void leavesTypenameOutOfTheRootFields() {
        assertThat(read(request("{ __typename viewer { login } }"))
                        .operations()
                        .getFirst()
                        .rootFields())
                .containsExactly("viewer");
    }

    /** The field a fragment selects at the root is a root field, whoever spelled it. */
    @Test
    void expandsFragmentsWhenReadingRootFieldsAndDepth() {
        var call = read(request("query { ...Everything } fragment Everything on Query { secrets { value } }"));

        assertThat(call.operations().getFirst().rootFields()).containsExactly("secrets");
        assertThat(call.operations().getFirst().depth()).isEqualTo(2);
    }

    @Test
    void expandsInlineFragmentsToo() {
        var call = read(request("{ ... on Query { viewer { login } } }"));

        assertThat(call.operations().getFirst().rootFields()).containsExactly("viewer");
    }

    // --- which operation runs ------------------------------------------------

    @Test
    void runsTheOperationNamedByOperationName() {
        var call = read("{\"query\":\"query A { viewer { login } } mutation B { wipe }\",\"operationName\":\"B\"}");

        assertThat(call.writes()).isTrue();
    }

    /** Two operations and no choice between them is a document the upstream refuses too. */
    @Test
    void refusesSeveralOperationsWithoutAName() {
        var refusal = refusal(() -> read(request("query A { viewer } mutation B { wipe }")));

        assertThat(refusal.code()).isEqualTo(ErrorCode.GRAPHQL_INVALID);
    }

    @Test
    void refusesAnOperationNameTheDocumentDoesNotDefine() {
        assertThat(refusal(() -> read("{\"query\":\"query A { viewer }\",\"operationName\":\"B\"}"))
                        .code())
                .isEqualTo(ErrorCode.GRAPHQL_INVALID);
    }

    @Test
    void refusesADocumentThatDoesNotParse() {
        assertThat(refusal(() -> read(request("{ viewer "))).code()).isEqualTo(ErrorCode.GRAPHQL_INVALID);
    }

    @Test
    void refusesTypeDefinitions() {
        assertThat(refusal(() -> read(request("type Query { viewer: String }"))).code())
                .isEqualTo(ErrorCode.GRAPHQL_INVALID);
    }

    @Test
    void refusesAFragmentThatSpreadsItself() {
        var refusal =
                refusal(() -> read(request("{ ...A } fragment A on Query { ...B } fragment B on Query { ...A }")));

        assertThat(refusal.code()).isEqualTo(ErrorCode.GRAPHQL_INVALID);
    }

    @Test
    void refusesASpreadOfAnUndefinedFragment() {
        assertThat(refusal(() -> read(request("{ ...Missing }"))).code()).isEqualTo(ErrorCode.GRAPHQL_INVALID);
    }

    // --- what a destination accepts -------------------------------------------

    @Test
    void refusesADocumentDeeperThanTheDestinationAccepts() {
        var shallow = graphQl(2, 0);

        var refusal = refusal(() -> post(shallow, request("{ a { b { c } } }"), true));

        assertThat(refusal.code()).isEqualTo(ErrorCode.GRAPHQL_TOO_COMPLEX);
        assertThat(refusal.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** A fragment spread three times is three sets of aliases, because that is what the server runs. */
    @Test
    void countsAliasesEveryTimeAFragmentIsSpread() {
        var strict = graphQl(0, 5);
        String document = "{ a: viewer { ...F } b: viewer { ...F } } fragment F on User { x: login y: login }";

        var refusal = refusal(() -> post(strict, request(document), true));

        assertThat(refusal.code()).isEqualTo(ErrorCode.GRAPHQL_TOO_COMPLEX);
    }

    @Test
    void admitsWhatIsWithinTheLimits() {
        var limited = graphQl(3, 2);

        var inspection = post(limited, request("{ a: viewer { b: login } }"), true);

        assertThat(inspection).isInstanceOf(GraphQlInspector.Read.class);
    }

    // --- how it travels -------------------------------------------------------

    @Test
    void readsAQuerySentAsGet() {
        var inspection = inspector.inspect(
                provider,
                HttpMethod.GET,
                new HttpHeaders(),
                null,
                "query=%7B%20viewer%20%7B%20login%20%7D%20%7D",
                true);

        assertThat(((GraphQlInspector.Read) inspection).call().readsOnly()).isTrue();
    }

    /** A GET is safe by definition, and a mutation carried by one would be stored as though it were. */
    @Test
    void refusesAMutationSentAsGet() {
        var refusal = refusal(() -> inspector.inspect(
                provider, HttpMethod.GET, new HttpHeaders(), null, "query=mutation%20%7B%20wipe%20%7D", false));

        assertThat(refusal.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void treatsAGetWithoutADocumentAsAnOrdinaryRequest() {
        assertThat(inspector.inspect(provider, HttpMethod.GET, new HttpHeaders(), null, null, true))
                .isInstanceOf(GraphQlInspector.NotAnOperation.class);
    }

    /** An upstream reading the last one while Janus read the first is the disagreement to refuse. */
    @Test
    void refusesAQueryParameterGivenTwice() {
        var refusal = refusal(() -> inspector.inspect(
                provider, HttpMethod.GET, new HttpHeaders(), null, "query=%7Ba%7D&query=mutation%7Bb%7D", false));

        assertThat(refusal.code()).isEqualTo(ErrorCode.GRAPHQL_INVALID);
    }

    @Test
    void readsTheOlderApplicationGraphqlBody() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/graphql"));

        var inspection = inspector.inspect(
                provider, HttpMethod.POST, headers, "mutation { wipe }".getBytes(StandardCharsets.UTF_8), null, true);

        assertThat(((GraphQlInspector.Read) inspection).call().writes()).isTrue();
    }

    @Test
    void refusesABodyThatIsNotJson() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        var refusal = refusal(() -> inspector.inspect(
                provider, HttpMethod.POST, headers, "{ viewer }".getBytes(StandardCharsets.UTF_8), null, false));

        assertThat(refusal.code()).isEqualTo(ErrorCode.GRAPHQL_INVALID);
    }

    // --- batches --------------------------------------------------------------

    @Test
    void aBatchOfQueriesOnlyReads() {
        var call = read("[" + request("{ a }") + "," + request("{ b }") + "]");

        assertThat(call.batch()).isTrue();
        assertThat(call.readsOnly()).isTrue();
    }

    /** The upstream runs every operation in a batch, so one mutation makes the whole batch a write. */
    @Test
    void aBatchHoldingAMutationWrites() {
        assertThat(read("[" + request("{ a }") + "," + request("mutation { b }") + "]")
                        .writes())
                .isTrue();
    }

    @Test
    void refusesABatchLargerThanTheDeploymentAccepts() {
        var batch = new StringBuilder("[");
        for (int i = 0; i <= properties.maxBatch(); i++)
            batch.append(i == 0 ? "" : ",").append(request("{ a }"));

        assertThat(refusal(() -> read(batch.append("]").toString())).code()).isEqualTo(ErrorCode.GRAPHQL_TOO_COMPLEX);
    }

    // --- persisted queries ----------------------------------------------------

    private static String persisted(String hash) {
        return "{\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"" + hash + "\"}}}";
    }

    private static String persisted(String hash, String query) {
        return "{\"query\":" + quote(query) + ",\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\""
                + hash + "\"}}}";
    }

    @Test
    void asksForTheDocumentBehindAHashItHasNeverSeenWhereReadingMatters() {
        assertThat(post(provider, persisted(GraphQlInspector.sha256("{ a }")), true))
                .isInstanceOf(GraphQlInspector.UnknownPersistedQuery.class);
    }

    /** Where nothing depends on reading it, the hash goes through unread, as a write would. */
    @Test
    void forwardsAnUnknownHashUnreadWhereNothingDependsOnIt() {
        var call = ((GraphQlInspector.Read) post(provider, persisted(GraphQlInspector.sha256("{ a }")), false)).call();

        assertThat(call.unreadable()).isTrue();
        assertThat(call.writes()).isTrue();
    }

    @Test
    void learnsAHashOnceItHasCheckedItAgainstItsDocument() {
        String query = "mutation { wipe }";
        String hash = GraphQlInspector.sha256(query);
        post(provider, persisted(hash, query), true);

        var call = ((GraphQlInspector.Read) post(provider, persisted(hash), true)).call();

        assertThat(call.writes()).isTrue();
        assertThat(call.unreadable()).isFalse();
    }

    /** Remembering a hash against the wrong document would let the next caller's hash read as ours. */
    @Test
    void refusesAHashThatDoesNotMatchItsDocument() {
        var refusal =
                refusal(() -> post(provider, persisted(GraphQlInspector.sha256("{ a }"), "mutation { b }"), true));

        assertThat(refusal.code()).isEqualTo(ErrorCode.GRAPHQL_INVALID);
    }

    @Test
    void keepsWhatOneDestinationLearnedFromAnother() {
        String query = "{ a }";
        String hash = GraphQlInspector.sha256(query);
        post(provider, persisted(hash, query), true);

        assertThat(post(graphQl(0, 0), persisted(hash), true))
                .isInstanceOf(GraphQlInspector.UnknownPersistedQuery.class);
    }

    // --- recognising GraphQL elsewhere ----------------------------------------

    @Test
    void recognisesADocumentSentToAnotherPath() {
        assertThat(inspector.looksLikeGraphQl(
                        json(), request("mutation { wipe }").getBytes(StandardCharsets.UTF_8), null))
                .isTrue();
        assertThat(inspector.looksLikeGraphQl(new HttpHeaders(), null, "query=%7B%20viewer%20%7D"))
                .isTrue();
    }

    /** A search API's own "query" parameter is not GraphQL, and a grant must not refuse it as though it were. */
    @Test
    void doesNotMistakeAnOrdinaryQueryParameterForGraphQl() {
        assertThat(inspector.looksLikeGraphQl(new HttpHeaders(), null, "query=miles+davis"))
                .isFalse();
        assertThat(inspector.looksLikeGraphQl(
                        json(), "{\"query\":\"miles davis\"}".getBytes(StandardCharsets.UTF_8), null))
                .isFalse();
    }

    @FunctionalInterface
    private interface ThrowingCallable extends org.assertj.core.api.ThrowableAssert.ThrowingCallable {}
}
