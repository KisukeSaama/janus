package io.janus.mcp;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;

import io.janus.shared.CorrelationIdFilter;
import io.janus.shared.NotFoundException;

/**
 * The MCP endpoint: JSON-RPC over the Streamable HTTP transport, stateless.
 *
 * <p>Everything an assistant asks for is a request with one answer — list the tools, call one — so
 * every POST is answered with a single JSON body and no session is kept. The transport's optional
 * parts exist for a server that pushes to its client, and this one never has anything to push: no
 * {@code Mcp-Session-Id}, no event stream, and GET answers 405 as the specification provides for.
 *
 * <p>Written against the protocol directly rather than through an SDK. What is spoken here is four
 * methods on a JSON envelope; an SDK would bring a second HTTP stack and a second JSON library to a
 * process that already has one of each, for a surface smaller than the code that would configure it.
 *
 * <p>By the time a request reaches this class, {@code McpBearerFilter} has put the person the token
 * acts for in the security context, and every tool runs as them.
 */
@RestController
@RequestMapping("/mcp")
public class McpServerController {
    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);

    /** Newest first. An unknown version asked for is answered with the newest, as the lifecycle says. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    private static final String INSTRUCTIONS = """
            Janus is a gateway that holds third-party API secrets and proxies calls to those APIs for \
            registered apps. You act as the signed-in Janus account that authorised you, with its role. \
            APIs (the catalogue) are shared and only administrators change them; apps, credentials and \
            grants belong to the account. A working setup is: an API, a credential for it (created by the \
            user in the Janus console — you never handle secrets), an app, and a grant linking the three. \
            To wire a code repository to Janus, call get_janus_md for its app and write the result to \
            JANUS.md. Ask before deleting anything.""";

    private final McpTools tools;
    private final String version;

    public McpServerController(McpTools tools) {
        this.tools = tools;
        var implementation = McpServerController.class.getPackage().getImplementationVersion();
        this.version = implementation == null ? "dev" : implementation;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> handle(
            @RequestBody Object body,
            @RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion) {
        if (!(body instanceof Map<?, ?> raw))
            return ResponseEntity.badRequest()
                    .body(error(null, -32600, "One JSON-RPC message per request; batches are not accepted"));
        @SuppressWarnings("unchecked")
        var message = (Map<String, Object>) raw;
        Object id = message.get("id");
        Object method = message.get("method");

        // A notification, or a response to something this server never asks: acknowledged, not answered.
        if (!(method instanceof String name) || id == null)
            return ResponseEntity.accepted().build();

        if (protocolVersion != null && !name.equals("initialize") && !PROTOCOL_VERSIONS.contains(protocolVersion))
            return ResponseEntity.badRequest()
                    .body(error(id, -32600, "Unsupported MCP-Protocol-Version " + protocolVersion));

        @SuppressWarnings("unchecked")
        var params = message.get("params") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.<String, Object>of();
        return ResponseEntity.ok(
                switch (name) {
                    case "initialize" -> result(id, initialize(params));
                    case "ping" -> result(id, Map.of());
                    case "tools/list" -> result(id, Map.of("tools", tools.definitions()));
                    case "tools/call" -> call(id, params);
                    default -> error(id, -32601, "Method not found: " + name);
                });
    }

    /** No stream to open: the server has nothing to say that was not asked for. */
    @GetMapping
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(HttpMethod.POST)
                .build();
    }

    /** No session to end, since none was begun. */
    @DeleteMapping
    public ResponseEntity<Void> end() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(HttpMethod.POST)
                .build();
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        Object asked = params.get("protocolVersion");
        String agreed = asked instanceof String v && PROTOCOL_VERSIONS.contains(v) ? v : PROTOCOL_VERSIONS.getFirst();
        var result = new LinkedHashMap<String, Object>();
        result.put("protocolVersion", agreed);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "janus", "title", "Janus", "version", version));
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    /**
     * Runs one tool. A refusal the model can act on — a missing field, a record it may not touch — is
     * a tool result marked as an error, which the model reads and corrects; only a call that names no
     * tool is a protocol error. Anything unexpected is logged here and answered without its message,
     * exactly as the console's own handler does.
     */
    private Map<String, Object> call(Object id, Map<String, Object> params) {
        Object name = params.get("name");
        if (!(name instanceof String tool) || !tools.exists(tool)) return error(id, -32602, "Unknown tool: " + name);
        @SuppressWarnings("unchecked")
        var arguments =
                params.get("arguments") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.<String, Object>of();
        try {
            return result(id, content(tools.call(tool, arguments).text(), false));
        } catch (McpTools.ToolError | IllegalArgumentException | NotFoundException ex) {
            return result(id, content(List.of(ex.getMessage()), true));
        } catch (AccessDeniedException ex) {
            return result(id, content(List.of("Not permitted for this account: " + ex.getMessage()), true));
        } catch (DataIntegrityViolationException ex) {
            return result(
                    id, content(List.of("The record is still referenced or conflicts with an existing record"), true));
        } catch (JacksonException ex) {
            return result(id, content(List.of("The arguments could not be read"), true));
        } catch (RuntimeException ex) {
            log.error("MCP tool {} failed [correlationId={}]", tool, CorrelationIdFilter.current(), ex);
            return result(
                    id,
                    content(
                            List.of("The tool failed inside Janus; correlation id " + CorrelationIdFilter.current()),
                            true));
        }
    }

    private static Map<String, Object> content(List<String> text, boolean isError) {
        var blocks = text.stream()
                .map(block -> Map.<String, Object>of("type", "text", "text", block))
                .toList();
        return Map.of("content", blocks, "isError", isError);
    }

    private static Map<String, Object> result(Object id, Object result) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("result", result);
        return envelope;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("error", Map.of("code", code, "message", message));
        return envelope;
    }
}
