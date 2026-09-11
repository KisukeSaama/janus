package io.janus.gateway.graphql;

import static org.assertj.core.api.Assertions.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import io.janus.providers.Provider;
import io.janus.testing.Fixtures;

/** The less travelled shapes of a GraphQL request, each of which must be read or refused, never guessed. */
class GraphQlInspectorEdgeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GraphQlProperties properties = GraphQlProperties.defaults();
    private final GraphQlInspector inspector =
            new GraphQlInspector(mapper, new PersistedQueries(properties), properties);
    private final Provider provider = Fixtures.provider(Fixtures.owner(), "github");

    {
        provider.applyGraphQl(new Provider.GraphQl("/graphql", 0, 0));
    }

    private static HttpHeaders json() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private GraphQlInspector.Inspection post(String body, boolean enforcing) {
        return inspector.inspect(
                provider, HttpMethod.POST, json(), body.getBytes(StandardCharsets.UTF_8), null, enforcing);
    }

    private GraphQlInspector.Inspection get(String query, boolean enforcing) {
        return inspector.inspect(provider, HttpMethod.GET, new HttpHeaders(), null, query, enforcing);
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String hashed(String query) {
        return "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"" + GraphQlInspector.sha256(query) + "\"}}";
    }

    @Test
    void readsAPersistedQuerySentAsGetOnceItIsKnown() {
        post("{\"query\":\"{ a }\",\"extensions\":" + hashed("{ a }") + "}", true);

        var inspection = get("extensions=" + encoded(hashed("{ a }")), true);

        assertThat(((GraphQlInspector.Read) inspection).call().readsOnly()).isTrue();
    }

    @Test
    void refusesExtensionsThatAreNotJson() {
        assertThatThrownBy(() -> get("extensions=" + encoded("{not json"), false))
                .isInstanceOf(GraphQlRefusal.class);
    }

    /** A trusted document is known to the upstream by an id and to nobody else. */
    @Test
    void forwardsATrustedDocumentUnreadOnlyWhereNothingDependsOnIt() {
        assertThat(((GraphQlInspector.Read) post("{\"documentId\":\"abc\"}", false))
                        .call()
                        .unreadable())
                .isTrue();
        assertThatThrownBy(() -> post("{\"documentId\":\"abc\"}", true)).isInstanceOf(GraphQlRefusal.class);
    }

    @Test
    void refusesAQueryThatIsNotAString() {
        assertThatThrownBy(() -> post("{\"query\":5}", false)).isInstanceOf(GraphQlRefusal.class);
    }

    @Test
    void refusesARequestWithoutAQuery() {
        assertThatThrownBy(() -> post("{\"variables\":{}}", false)).isInstanceOf(GraphQlRefusal.class);
    }

    @Test
    void refusesABodyThatIsNotJson() {
        assertThatThrownBy(() -> post("{ viewer }", false)).isInstanceOf(GraphQlRefusal.class);
    }

    @Test
    void refusesAnEmptyBatchAndABatchOfNonObjects() {
        assertThatThrownBy(() -> post("[]", false)).isInstanceOf(GraphQlRefusal.class);
        assertThatThrownBy(() -> post("[1]", false)).isInstanceOf(GraphQlRefusal.class);
    }

    @Test
    void asksABatchForEveryDocumentWhenOneOfItsHashesIsUnknown() {
        var inspection = post("[{\"query\":\"{ a }\"},{\"extensions\":" + hashed("{ b }") + "}]", true);

        assertThat(inspection).isEqualTo(new GraphQlInspector.UnknownPersistedQuery(2, true));
    }

    @Test
    void treatsABatchHoldingAnUnreadDocumentAsUnread() {
        var call = ((GraphQlInspector.Read) post("[{\"query\":\"{ a }\"},{\"documentId\":\"x\"}]", false)).call();

        assertThat(call.unreadable()).isTrue();
    }

    @Test
    void takesTheOperationNameOfAnApplicationGraphqlBodyFromTheQueryString() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/graphql"));

        var inspection = inspector.inspect(
                provider,
                HttpMethod.POST,
                headers,
                "query A { a } mutation B { b }".getBytes(StandardCharsets.UTF_8),
                "operationName=B",
                false);

        assertThat(((GraphQlInspector.Read) inspection).call().writes()).isTrue();
    }

    @Test
    void refusesAContentTypeNobodyCouldRead() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "not a type");

        assertThatThrownBy(() -> inspector.inspect(
                        provider, HttpMethod.POST, headers, "{}".getBytes(StandardCharsets.UTF_8), null, false))
                .isInstanceOf(GraphQlRefusal.class);
    }

    @Test
    void refusesAGetCarryingABody() {
        assertThatThrownBy(() -> inspector.inspect(
                        provider,
                        HttpMethod.GET,
                        json(),
                        "{\"query\":\"{ a }\"}".getBytes(StandardCharsets.UTF_8),
                        null,
                        false))
                .isInstanceOf(GraphQlRefusal.class);
    }

    @Test
    void readsAWebSocketPayloadLikeARequestBody() {
        var payload = mapper.readTree("{\"query\":\"subscription { issues { id } }\"}");

        var call = ((GraphQlInspector.Read) inspector.inspectPayload(provider, payload, true)).call();

        assertThat(call.subscribes()).isTrue();
        assertThat(call.governingType()).isEqualTo(OperationType.SUBSCRIPTION);
    }

    @Test
    void recognisesGraphQlByItsContentTypeOrItsPersistedQuery() {
        var graphql = new HttpHeaders();
        graphql.setContentType(MediaType.parseMediaType("application/graphql"));
        var unreadable = new HttpHeaders();
        unreadable.set(HttpHeaders.CONTENT_TYPE, "not a type");

        assertThat(inspector.looksLikeGraphQl(graphql, null, null)).isTrue();
        assertThat(inspector.looksLikeGraphQl(
                        unreadable, "{\"documentId\":\"x\"}".getBytes(StandardCharsets.UTF_8), null))
                .isTrue();
        assertThat(inspector.looksLikeGraphQl(json(), "not json".getBytes(StandardCharsets.UTF_8), null))
                .isFalse();
        assertThat(inspector.looksLikeGraphQl(new HttpHeaders(), null, "extensions=x"))
                .isTrue();
        assertThat(inspector.looksLikeGraphQl(new HttpHeaders(), null, null)).isFalse();
    }

    @Test
    void describesACallForTheJournalAndForTheIdentityMemory() {
        var call = new GraphQlCall(
                java.util.List.of(
                        new GraphQlOperation(OperationType.QUERY, "Viewer", java.util.Set.of("viewer"), 2, 0),
                        new GraphQlOperation(OperationType.MUTATION, null, java.util.Set.of("wipe"), 1, 0)),
                true);

        assertThat(call.describe()).isEqualTo("graphql query Viewer [viewer]; mutation [wipe]");
        assertThat(call.shape()).isEqualTo("query:viewer;mutation:wipe");
        assertThat(call.governingType()).isEqualTo(OperationType.MUTATION);
        assertThat(GraphQlCall.unread().describe()).contains("not read");
        assertThat(OperationType.parse(" query ")).isEqualTo(OperationType.QUERY);
    }
}
