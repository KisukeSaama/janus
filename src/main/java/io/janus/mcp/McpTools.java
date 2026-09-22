package io.janus.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

import io.janus.accounts.AccessScope;
import io.janus.agents.AgentFileService;
import io.janus.applications.ApplicationRequest;
import io.janus.applications.ApplicationService;
import io.janus.credentials.CredentialService;
import io.janus.grants.GrantRequest;
import io.janus.grants.GrantService;
import io.janus.providers.ProviderRequest;
import io.janus.providers.ProviderService;
import io.janus.shared.NotFoundException;

/**
 * What an assistant may do over MCP, and nothing it may not already do in the console.
 *
 * <p>Every tool is a call into the service the console's own controller calls, on the same thread,
 * with the same person signed in. So every rule is the one that already holds: an ordinary account
 * cannot touch the catalogue, nobody sees another account's records, every change is journalled. The
 * tools add no authority; they add a second way in.
 *
 * <p>Two things are withheld that the console does offer, both deliberately. No secret is ever stored
 * or returned, and no API key is ever issued: those are values a person handles, and a conversation
 * with a model is logged, cached and sent places. Registering an app therefore returns it without its
 * key, and the key is issued in the console.
 *
 * <p>The descriptions and schemas live in {@code mcp/tools.json}, where they read as the documentation
 * they are. An update tool shares its create tool's shape with every field made optional, and takes
 * what it is given as a patch over what is stored: a model asked to "raise the rate limit" should not
 * have to restate thirty fields to do it.
 */
@Component
public class McpTools {
    private final ObjectMapper mapper;
    private final Validator validator;
    private final AccessScope scope;
    private final ProviderService providers;
    private final ApplicationService applications;
    private final CredentialService credentials;
    private final GrantService grants;
    private final AgentFileService agentFiles;

    private final List<Map<String, Object>> definitions;
    private final Map<String, Function<Map<String, Object>, Result>> handlers;

    /** A tool's answer: the text an assistant reads, one block per entry. */
    public record Result(List<String> text) {
        static Result of(String... text) {
            return new Result(List.of(text));
        }
    }

    /** A refusal the assistant can act on. Its message is returned to it verbatim. */
    public static class ToolError extends RuntimeException {
        public ToolError(String message) {
            super(message);
        }
    }

    public McpTools(
            ObjectMapper mapper,
            Validator validator,
            AccessScope scope,
            ProviderService providers,
            ApplicationService applications,
            CredentialService credentials,
            GrantService grants,
            AgentFileService agentFiles) {
        this.mapper = mapper;
        this.validator = validator;
        this.scope = scope;
        this.providers = providers;
        this.applications = applications;
        this.credentials = credentials;
        this.grants = grants;
        this.agentFiles = agentFiles;
        this.handlers = handlers();
        this.definitions = load();
    }

    public List<Map<String, Object>> definitions() {
        return definitions;
    }

    public boolean exists(String name) {
        return handlers.containsKey(name);
    }

    public Result call(String name, Map<String, Object> arguments) {
        var handler = handlers.get(name);
        if (handler == null) throw new NoSuchElementException(name);
        return handler.apply(arguments == null ? Map.of() : arguments);
    }

    /* ── Handlers ──────────────────────────────────────────────────────── */

    private Map<String, Function<Map<String, Object>, Result>> handlers() {
        var all = new LinkedHashMap<String, Function<Map<String, Object>, Result>>();
        all.put("whoami", args -> whoami());

        all.put(
                "list_apis",
                args -> json(providers.catalog(
                        string(args, "query", ""), integer(args, "page", 0), integer(args, "size", 20))));
        all.put("get_api", args -> {
            if (args.get("id") != null) return json(providers.get(uuid(args, "id")));
            if (args.get("slug") != null) return json(providers.getBySlug(string(args, "slug", "")));
            throw new ToolError("Pass either id or slug");
        });
        all.put("create_api", args -> json(providers.create(create(args, ProviderRequest.class))));
        all.put("update_api", args -> {
            var id = uuid(args, "id");
            return json(providers.update(id, patch(providers.get(id), args, ProviderRequest.class)));
        });
        all.put("delete_api", args -> {
            providers.delete(uuid(args, "id"));
            return json(Map.of("deleted", true));
        });
        all.put("ping_api", args -> json(providers.ping(uuid(args, "id"))));

        all.put("list_apps", args -> json(Map.of("apps", applications.list())));
        all.put("create_app", args -> {
            // The key is issued and thrown away here: it exists only as a hash from this point on, and
            // the person who needs it issues their own from the console.
            var issued = applications.create(create(args, ApplicationRequest.class));
            var body = new LinkedHashMap<String, Object>();
            body.put("application", issued.application());
            body.put(
                    "apiKey",
                    "Not disclosed over MCP. Ask the user to open this app in the Janus console, choose "
                            + "'Rotate key', and store the key as JANUS_API_KEY in the service's secret store.");
            return json(body);
        });
        all.put("update_app", args -> {
            var id = uuid(args, "id");
            var current = applications.list().stream()
                    .filter(app -> app.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Application not found"));
            return json(applications.update(id, patch(current, args, ApplicationRequest.class)));
        });
        all.put("delete_app", args -> {
            applications.delete(uuid(args, "id"));
            return json(Map.of("deleted", true));
        });

        all.put("list_credentials", args -> json(Map.of("credentials", credentials.list())));

        all.put("list_grants", args -> json(Map.of("grants", grants.list())));
        all.put("create_grant", args -> json(grants.create(create(args, GrantRequest.class))));
        all.put("update_grant", args -> {
            var id = uuid(args, "id");
            var current = grants.list().stream()
                    .filter(grant -> grant.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Grant not found"));
            return json(grants.update(id, patch(current, args, GrantRequest.class)));
        });
        all.put("delete_grant", args -> {
            grants.delete(uuid(args, "id"));
            return json(Map.of("deleted", true));
        });

        all.put("get_janus_md", args -> {
            var file = agentFiles.render(args.get("applicationId") == null ? null : uuid(args, "applicationId"));
            // Two blocks rather than one JSON document: the file has to reach the repository exactly as
            // written, and a model copying Markdown out of an escaped JSON string is a model that can
            // get the escaping wrong.
            return Result.of(
                    "Write the next block verbatim to " + file.fileName() + " at the root of the service's "
                            + "repository (it lists " + file.apiCount() + " API(s)), then make sure CLAUDE.md or "
                            + "AGENTS.md references it, e.g. with a line '@" + file.fileName() + "'. It contains no "
                            + "secret and is meant to be committed.",
                    file.content());
        });
        return all;
    }

    private Result whoami() {
        var user = scope.current();
        var body = new LinkedHashMap<String, Object>();
        body.put("username", user.getUsername());
        body.put("displayName", user.displayName());
        body.put("role", user.role().name());
        body.put("managesApiCatalogue", user.role().administers());
        body.put("privateDestinations", providers.capabilities().privateDestinations());
        scope.assistant().ifPresent(assistant -> body.put("assistant", assistant.clientName()));
        return json(body);
    }

    /* ── Arguments ─────────────────────────────────────────────────────── */

    /** What a console form pre-fills: a record created from a conversation is live unless told not to be. */
    private static Map<String, Object> withDefaults(Map<String, Object> args) {
        var filled = new LinkedHashMap<>(args);
        filled.putIfAbsent("enabled", true);
        return filled;
    }

    /**
     * The stored record, with what the assistant passed laid over it. Read through the same response
     * the console reads, so a field this cannot see is a field the console could not have sent either.
     */
    private <T extends Record> T patch(Object current, Map<String, Object> args, Class<T> type) {
        rejectUnknown(args, type);
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = new LinkedHashMap<>(mapper.convertValue(current, Map.class));
        args.forEach((key, value) -> {
            if (!key.equals("id")) merged.put(key, value);
        });
        return request(merged, type);
    }

    /**
     * Refuses a field the request does not have, rather than dropping it. A model that misspells
     * {@code rateLimitPerMinute} should be told, not answered with a success that changed nothing.
     */
    private static void rejectUnknown(Map<String, Object> args, Class<? extends Record> type) {
        var accepted = fields(type);
        var unknown = args.keySet().stream()
                .filter(key -> !accepted.contains(key) && !key.equals("id"))
                .toList();
        if (!unknown.isEmpty()) throw new ToolError("Unknown argument(s) " + unknown + "; accepted: " + accepted);
    }

    private static Set<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A new record from what the assistant passed, checked like a patch is. */
    private <T extends Record> T create(Map<String, Object> args, Class<T> type) {
        rejectUnknown(args, type);
        return request(withDefaults(args), type);
    }

    /** Converts, then validates exactly as the console's controller would with {@code @Valid}. */
    private <T extends Record> T request(Map<String, Object> args, Class<T> type) {
        // A patch also carries the stored record's read-only fields — identifiers, timestamps — which
        // are simply not the request's to read.
        var accepted = fields(type);
        var known = new LinkedHashMap<String, Object>();
        args.forEach((key, value) -> {
            if (accepted.contains(key)) known.put(key, value);
        });

        T request;
        try {
            request = mapper.convertValue(known, type);
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new ToolError(unusable(ex));
        }
        Set<ConstraintViolation<T>> violations = validator.validate(request);
        if (!violations.isEmpty())
            throw new ToolError("Invalid arguments: "
                    + violations.stream()
                            .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                            .sorted()
                            .collect(Collectors.joining("; ")));
        return request;
    }

    /** Names the field the parser choked on, without quoting the value, like the console's handler. */
    private static String unusable(Exception ex) {
        Throwable cause = ex;
        while (cause != null && !(cause instanceof DatabindException)) cause = cause.getCause();
        if (cause instanceof DatabindException databind) {
            String path = databind.getPath().stream()
                    .map(DatabindException.Reference::getPropertyName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            if (!path.isEmpty()) return "Argument '" + path + "' is missing or has an unusable value";
        }
        return "The arguments could not be read";
    }

    private static UUID uuid(Map<String, Object> args, String name) {
        var value = args.get(name);
        if (value == null) throw new ToolError("'" + name + "' is required");
        try {
            return UUID.fromString(value.toString().trim());
        } catch (IllegalArgumentException ex) {
            throw new ToolError("'" + name + "' must be a UUID");
        }
    }

    private static String string(Map<String, Object> args, String name, String fallback) {
        var value = args.get(name);
        return value == null ? fallback : value.toString();
    }

    private static int integer(Map<String, Object> args, String name, int fallback) {
        var value = args.get(name);
        if (value == null) return fallback;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException ex) {
            throw new ToolError("'" + name + "' must be an integer");
        }
    }

    private Result json(Object value) {
        return Result.of(mapper.writeValueAsString(value));
    }

    /* ── Definitions ───────────────────────────────────────────────────── */

    /**
     * Reads the catalogue once, at startup, and refuses to start if it and the handlers above disagree:
     * a tool advertised with nothing behind it, or implemented and never advertised, is a mistake
     * nobody would otherwise notice until a model tripped on it.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> load() {
        Map<String, Object> file;
        try (var in = new ClassPathResource("mcp/tools.json").getInputStream()) {
            file = mapper.readValue(in, Map.class);
        } catch (IOException ex) {
            throw new UncheckedIOException("mcp/tools.json could not be read", ex);
        }
        var shapes = (Map<String, Map<String, Object>>) file.get("shapes");
        var tools = (List<Map<String, Object>>) file.get("tools");

        var names = tools.stream().map(tool -> (String) tool.get("name")).toList();
        if (!new HashSet<>(names).equals(handlers.keySet()))
            throw new IllegalStateException(
                    "mcp/tools.json declares " + names + " but handlers exist for " + handlers.keySet());

        return tools.stream().map(tool -> definition(tool, shapes)).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> definition(Map<String, Object> tool, Map<String, Map<String, Object>> shapes) {
        Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
        if (schema == null) {
            var shape = shapes.get((String) tool.get("shape"));
            var properties = new LinkedHashMap<String, Object>();
            schema = new LinkedHashMap<>();
            schema.put("type", "object");
            if ("update".equals(tool.get("mode"))) {
                properties.put("id", Map.of("type", "string", "format", "uuid", "description", "What to change."));
                properties.putAll((Map<String, Object>) shape.get("properties"));
                schema.put("required", List.of("id"));
            } else {
                properties.putAll((Map<String, Object>) shape.get("properties"));
                schema.put("required", shape.get("required"));
            }
            schema.put("properties", properties);
            schema.put("additionalProperties", false);
        }

        boolean readOnly = Boolean.TRUE.equals(tool.get("readOnly"));
        var annotations = new LinkedHashMap<String, Object>();
        annotations.put("title", tool.get("title"));
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", Boolean.TRUE.equals(tool.get("destructive")));
        annotations.put(
                "idempotentHint", readOnly || String.valueOf(tool.get("name")).startsWith("update_"));
        annotations.put("openWorldHint", Boolean.TRUE.equals(tool.get("openWorld")));

        var definition = new LinkedHashMap<String, Object>();
        definition.put("name", tool.get("name"));
        definition.put("title", tool.get("title"));
        definition.put("description", tool.get("description"));
        definition.put("inputSchema", schema);
        definition.put("annotations", annotations);
        return definition;
    }
}
