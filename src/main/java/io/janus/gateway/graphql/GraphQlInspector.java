package io.janus.gateway.graphql;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import graphql.language.*;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import graphql.parser.ParserEnvironment;
import graphql.parser.ParserOptions;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.janus.providers.Provider;
import io.janus.shared.ErrorCode;

/**
 * Reads a GraphQL request far enough to decide what it is, and no further.
 *
 * <p>Janus never executes a document and never validates one against a schema it does not have. What
 * it reads is the part every decision depends on: which operation will run, of what kind, which root
 * fields it selects, how deep it nests and how many fields it aliases. Everything else about the
 * request, variables included, travels untouched.
 *
 * <p>The reading is conservative wherever it has to guess. A field under {@code @skip} or
 * {@code @include} is counted as selected, because whether it runs depends on a variable; a fragment
 * is counted every time it is spread, because that is what the upstream will do. A document that
 * cannot be read is refused rather than forwarded: the upstream would refuse it too, and forwarding
 * what was not read would be forwarding what was not authorised.
 *
 * <p>The parser is bounded by graphql-java's own operation limits (characters, tokens, rule depth),
 * which is what keeps a hostile document from costing more to read than to refuse.
 */
@Component
public class GraphQlInspector {
    private static final ParserOptions OPTIONS = ParserOptions.getDefaultOperationParserOptions();

    /** What {@code application/graphql} was, before JSON became the only format servers agree on. */
    private static final MediaType GRAPHQL = MediaType.parseMediaType("application/graphql");

    private final ObjectMapper mapper;
    private final PersistedQueries persisted;
    private final GraphQlProperties properties;

    public GraphQlInspector(ObjectMapper mapper, PersistedQueries persisted, GraphQlProperties properties) {
        this.mapper = mapper;
        this.persisted = persisted;
        this.properties = properties;
    }

    /** What reading a request produced. */
    public sealed interface Inspection permits Read, NotAnOperation, UnknownPersistedQuery {}

    /** Operations Janus could read, or a persisted query it did not need to. */
    public record Read(GraphQlCall call) implements Inspection {}

    /**
     * No document at all: a {@code GET} for the endpoint's own page, say. Nothing can execute without
     * one, so it is an ordinary request to an ordinary path.
     */
    public record NotAnOperation() implements Inspection {}

    /**
     * Only persisted-query hashes Janus has never seen, where something depends on reading them. It is
     * answered as an APQ server answers, which makes the client send the documents.
     *
     * @param entries how many requests it held, so a batch is answered with as many errors
     */
    public record UnknownPersistedQuery(int entries, boolean batch) implements Inspection {}

    /**
     * Reads a request made to a GraphQL endpoint.
     *
     * @param enforcing whether anything depends on reading the document: a grant narrowing GraphQL, or
     *     a destination limiting depth or aliases. Where nothing does, a persisted query Janus cannot
     *     read is forwarded unread rather than sent back.
     */
    public Inspection inspect(
            Provider provider,
            HttpMethod method,
            HttpHeaders headers,
            byte[] body,
            String rawQuery,
            boolean enforcing) {
        boolean reads = method == HttpMethod.GET || method == HttpMethod.HEAD;
        boolean hasBody = body != null && body.length > 0;
        if (!hasBody) {
            var parameters = parameters(rawQuery);
            if (!parameters.containsKey("query")
                    && !parameters.containsKey("extensions")
                    && !parameters.containsKey("documentId")) return new NotAnOperation();
            var entry = mapper.createObjectNode();
            parameters.forEach(entry::put);
            if (parameters.containsKey("extensions")) entry.set("extensions", json(parameters.get("extensions")));
            return single(provider, entry, reads, enforcing);
        }
        if (reads) throw GraphQlRefusal.invalid("A GET request carries its operation in the query string");

        MediaType type;
        try {
            type = headers.getContentType();
        } catch (InvalidMediaTypeException ex) {
            throw GraphQlRefusal.invalid("The Content-Type header could not be read");
        }
        if (type != null && type.isCompatibleWith(GRAPHQL)) {
            var entry = mapper.createObjectNode();
            entry.put("query", new String(body, StandardCharsets.UTF_8));
            String operationName = parameters(rawQuery).get("operationName");
            if (operationName != null) entry.put("operationName", operationName);
            return single(provider, entry, false, enforcing);
        }
        if (type != null && !isJson(type))
            throw GraphQlRefusal.invalid("A GraphQL request is sent as application/json");

        var document = json(body);
        if (document.isArray()) return batch(provider, document, enforcing);
        return single(provider, document, false, enforcing);
    }

    /**
     * Reads one operation carried by a WebSocket {@code subscribe} or {@code start} message, whose
     * payload has the same shape as a JSON request body.
     */
    public Inspection inspectPayload(Provider provider, JsonNode payload, boolean enforcing) {
        return single(provider, payload, false, enforcing);
    }

    /**
     * Whether a request made somewhere other than the declared endpoint is nonetheless a GraphQL one.
     * Asked only on behalf of a grant that narrows GraphQL, which would otherwise be stepped around
     * by sending the same document to a second route the upstream also executes it on.
     */
    public boolean looksLikeGraphQl(HttpHeaders headers, byte[] body, String rawQuery) {
        try {
            var type = headers.getContentType();
            if (type != null && type.isCompatibleWith(GRAPHQL)) return true;
        } catch (InvalidMediaTypeException ex) {
            // Not a type anybody could read; the body decides.
        }
        if (body != null && body.length > 0) {
            JsonNode document;
            try {
                document = mapper.readTree(body);
            } catch (JacksonException ex) {
                return false;
            }
            var entries = document.isArray() ? document : List.of(document);
            for (JsonNode entry : entries) if (entry.isObject() && carriesOperation(entry)) return true;
        }
        var parameters = parameters(rawQuery, false);
        return parameters.containsKey("extensions")
                || parameters.containsKey("documentId")
                || parsesAsOperation(parameters.get("query"));
    }

    private boolean carriesOperation(JsonNode entry) {
        if (entry.has("documentId") || entry.path("extensions").has("persistedQuery")) return true;
        var query = entry.get("query");
        return query != null && query.isString() && parsesAsOperation(query.stringValue());
    }

    private static boolean parsesAsOperation(String query) {
        if (query == null || query.isBlank()) return false;
        try {
            return !parse(query).getDefinitionsOfType(OperationDefinition.class).isEmpty();
        } catch (InvalidSyntaxException ex) {
            return false;
        }
    }

    private Inspection batch(Provider provider, JsonNode entries, boolean enforcing) {
        if (entries.isEmpty()) throw GraphQlRefusal.invalid("A batch carries at least one operation");
        if (entries.size() > properties.maxBatch())
            throw GraphQlRefusal.tooComplex("A batch of " + entries.size() + " operations is more than the "
                    + properties.maxBatch() + " this deployment accepts");
        var operations = new ArrayList<GraphQlOperation>();
        boolean unread = false;
        int unknown = 0;
        for (JsonNode entry : entries) {
            switch (single(provider, entry, false, enforcing)) {
                case Read read when read.call().unreadable() -> unread = true;
                case Read read -> operations.addAll(read.call().operations());
                case UnknownPersistedQuery ignored -> unknown++;
                case NotAnOperation ignored -> throw GraphQlRefusal.invalid("Every request in a batch carries a query");
            }
        }
        if (unknown > 0) return new UnknownPersistedQuery(entries.size(), true);
        return new Read(unread ? GraphQlCall.unread() : new GraphQlCall(operations, true));
    }

    private Inspection single(Provider provider, JsonNode entry, boolean viaGet, boolean enforcing) {
        if (entry == null || !entry.isObject()) throw GraphQlRefusal.invalid("A GraphQL request is a JSON object");
        String query = string(entry, "query");
        String operationName = string(entry, "operationName");
        String hash = string(entry.path("extensions").path("persistedQuery"), "sha256Hash");

        if (query == null) {
            if (hash != null) {
                query = persisted.recall(provider.getId(), normaliseHash(hash));
                if (query == null)
                    return enforcing ? new UnknownPersistedQuery(1, false) : new Read(GraphQlCall.unread());
            } else if (entry.has("documentId")) {
                // A trusted document, known to the upstream by an identifier and to nobody else.
                if (enforcing)
                    throw GraphQlRefusal.invalid(
                            "A document sent by its identifier cannot be read, and this call needs "
                                    + "reading: send the document itself");
                return new Read(GraphQlCall.unread());
            } else {
                throw GraphQlRefusal.invalid("A GraphQL request carries a query");
            }
        } else if (hash != null) {
            // A hash and a document together is the client teaching the server. Janus learns only
            // what it checked: a hash that does not match its document is refused rather than
            // remembered, or the next caller sending that hash would be read as somebody else's query.
            String expected = normaliseHash(hash);
            if (!expected.equals(sha256(query)))
                throw GraphQlRefusal.invalid("The persisted query hash does not match the document it arrived with");
            persisted.remember(provider.getId(), expected, query);
        }

        var operation = analyse(query, operationName, provider);
        if (viaGet && operation.type() == OperationType.MUTATION)
            throw new GraphQlRefusal(
                    HttpStatus.METHOD_NOT_ALLOWED,
                    ErrorCode.GRAPHQL_INVALID,
                    "A mutation is sent with POST, never with GET");
        return new Read(GraphQlCall.of(operation));
    }

    /** Parses the document, picks the operation that will run, and measures it. */
    GraphQlOperation analyse(String query, String operationName, Provider provider) {
        Document document;
        try {
            document = parse(query);
        } catch (InvalidSyntaxException ex) {
            throw GraphQlRefusal.invalid(ex.getMessage());
        }

        var operations = new ArrayList<OperationDefinition>();
        var fragments = new HashMap<String, FragmentDefinition>();
        for (Definition<?> definition : document.getDefinitions()) {
            if (definition instanceof OperationDefinition operation) operations.add(operation);
            else if (definition instanceof FragmentDefinition fragment) {
                if (fragments.putIfAbsent(fragment.getName(), fragment) != null)
                    throw GraphQlRefusal.invalid("The fragment " + fragment.getName() + " is defined twice");
            } else throw GraphQlRefusal.invalid("A request carries operations and fragments, not type definitions");
        }

        var operation = select(operations, operationName);
        var walk = new Walk(fragments);
        var selection = operation.getSelectionSet();
        var result = new GraphQlOperation(
                OperationType.of(operation.getOperation()),
                operation.getName(),
                walk.rootFields(selection),
                walk.depth(selection),
                walk.aliases(selection));

        if (provider.getGraphqlMaxDepth() > 0 && result.depth() > provider.getGraphqlMaxDepth())
            throw GraphQlRefusal.tooComplex("This operation nests " + result.depth()
                    + " levels deep, and this API accepts " + provider.getGraphqlMaxDepth());
        if (provider.getGraphqlMaxAliases() > 0 && result.aliases() > provider.getGraphqlMaxAliases())
            throw GraphQlRefusal.tooComplex("This operation aliases " + result.aliases()
                    + " fields, and this API accepts " + provider.getGraphqlMaxAliases());
        return result;
    }

    private static OperationDefinition select(List<OperationDefinition> operations, String operationName) {
        if (operations.isEmpty()) throw GraphQlRefusal.invalid("The document defines no operation");
        if (operationName != null && !operationName.isBlank()) {
            return operations.stream()
                    .filter(operation -> operationName.equals(operation.getName()))
                    .findFirst()
                    .orElseThrow(
                            () -> GraphQlRefusal.invalid("The document defines no operation named " + operationName));
        }
        if (operations.size() > 1)
            throw GraphQlRefusal.invalid(
                    "The document defines several operations and operationName names none of them");
        return operations.getFirst();
    }

    private static Document parse(String query) {
        return Parser.parse(ParserEnvironment.newParserEnvironment()
                .document(query)
                .parserOptions(OPTIONS)
                .build());
    }

    /**
     * Measures a selection with its fragments expanded. Each fragment is measured once and the result
     * reused, so a document spreading the same fragment many times costs its size to read rather than
     * the size of what it expands to.
     */
    private static final class Walk {
        private final Map<String, FragmentDefinition> fragments;
        private final Map<String, Integer> depths = new HashMap<>();
        private final Map<String, Long> aliasCounts = new HashMap<>();
        private final Set<String> visiting = new HashSet<>();

        Walk(Map<String, FragmentDefinition> fragments) {
            this.fragments = fragments;
        }

        Set<String> rootFields(SelectionSet selection) {
            var fields = new TreeSet<String>();
            collectRoots(selection, fields, new HashSet<>());
            return fields;
        }

        private void collectRoots(SelectionSet selection, Set<String> fields, Set<String> seen) {
            if (selection == null) return;
            for (Selection<?> item : selection.getSelections()) {
                switch (item) {
                    case Field field -> {
                        if (!"__typename".equals(field.getName())) fields.add(field.getName());
                    }
                    case InlineFragment inline -> collectRoots(inline.getSelectionSet(), fields, seen);
                    case FragmentSpread spread -> {
                        if (seen.add(spread.getName()))
                            collectRoots(fragment(spread.getName()).getSelectionSet(), fields, seen);
                    }
                    default -> throw GraphQlRefusal.invalid("The document selects something that is not a field");
                }
            }
        }

        int depth(SelectionSet selection) {
            if (selection == null) return 0;
            int deepest = 0;
            for (Selection<?> item : selection.getSelections()) {
                int depth =
                        switch (item) {
                            case Field field -> 1 + depth(field.getSelectionSet());
                            case InlineFragment inline -> depth(inline.getSelectionSet());
                            case FragmentSpread spread -> fragmentDepth(spread.getName());
                            default -> 0;
                        };
                deepest = Math.max(deepest, depth);
            }
            return deepest;
        }

        int aliases(SelectionSet selection) {
            return (int) Math.min(Integer.MAX_VALUE, aliasCount(selection));
        }

        private long aliasCount(SelectionSet selection) {
            if (selection == null) return 0;
            long count = 0;
            for (Selection<?> item : selection.getSelections()) {
                long here =
                        switch (item) {
                            case Field field ->
                                (field.getAlias() == null ? 0 : 1) + aliasCount(field.getSelectionSet());
                            case InlineFragment inline -> aliasCount(inline.getSelectionSet());
                            case FragmentSpread spread -> fragmentAliases(spread.getName());
                            default -> 0;
                        };
                // Saturating: a fragment spread inside a fragment spread multiplies, and a document
                // built to overflow a counter should read as enormous rather than as negative.
                count = Math.min(Integer.MAX_VALUE, count + here);
            }
            return count;
        }

        private int fragmentDepth(String name) {
            var known = depths.get(name);
            if (known != null) return known;
            enter(name);
            int depth = depth(fragment(name).getSelectionSet());
            visiting.remove(name);
            depths.put(name, depth);
            return depth;
        }

        private long fragmentAliases(String name) {
            var known = aliasCounts.get(name);
            if (known != null) return known;
            enter(name);
            long count = aliasCount(fragment(name).getSelectionSet());
            visiting.remove(name);
            aliasCounts.put(name, count);
            return count;
        }

        /** A fragment that spreads itself, directly or not, would expand forever. */
        private void enter(String name) {
            if (!visiting.add(name)) throw GraphQlRefusal.invalid("The fragment " + name + " spreads itself");
        }

        private FragmentDefinition fragment(String name) {
            var fragment = fragments.get(name);
            if (fragment == null)
                throw GraphQlRefusal.invalid("The document spreads a fragment named " + name + " and defines none");
            return fragment;
        }
    }

    private JsonNode json(byte[] body) {
        try {
            return mapper.readTree(body);
        } catch (JacksonException ex) {
            throw GraphQlRefusal.invalid("The body is not valid JSON");
        }
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (JacksonException ex) {
            throw GraphQlRefusal.invalid("The extensions parameter is not valid JSON");
        }
    }

    /** A string member, or null when absent or null; anything else there is a malformed request. */
    private static String string(JsonNode node, String name) {
        var value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw GraphQlRefusal.invalid("The " + name + " member is a string");
        return value.stringValue();
    }

    private static boolean isJson(MediaType type) {
        return type.isCompatibleWith(MediaType.APPLICATION_JSON)
                || type.getSubtype().endsWith("+json");
    }

    private static Map<String, String> parameters(String rawQuery) {
        return parameters(rawQuery, true);
    }

    /**
     * The query string, form-decoded. A value repeated is refused where it matters, because an
     * upstream reading the last {@code query} while Janus read the first is exactly the disagreement a
     * caller would use to send one document past a check made on another.
     */
    private static Map<String, String> parameters(String rawQuery, boolean strict) {
        var parameters = new HashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) return parameters;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String name;
            String value;
            try {
                name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
                value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                if (strict) throw GraphQlRefusal.invalid("The query string could not be decoded");
                return Map.of();
            }
            if (parameters.putIfAbsent(name, value) != null
                    && strict
                    && (name.equals("query") || name.equals("operationName") || name.equals("extensions")))
                throw GraphQlRefusal.invalid("The " + name + " parameter is given more than once");
        }
        return parameters;
    }

    private static String normaliseHash(String hash) {
        return hash.trim().toLowerCase(Locale.ROOT);
    }

    static String sha256(String document) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(document.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }
}
