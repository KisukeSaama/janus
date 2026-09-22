package io.janus.mcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.*;

/**
 * The console's side of MCP: the consent screen, and the list of assistants somebody has let in.
 *
 * <p>Behind the console's session and its CSRF protection like any other administrative write, which
 * is the point. Agreeing is the one step in the flow a person has to take, and it is taken where that
 * person is already known.
 */
@RestController
@RequestMapping("/api/admin/mcp")
public class McpConsoleController {
    private final McpOAuthService oauth;

    public McpConsoleController(McpOAuthService oauth) {
        this.oauth = oauth;
    }

    /** Where an assistant is pointed, for the console to show and copy. */
    @GetMapping("/server")
    public Map<String, String> server() {
        return Map.of("url", oauth.resource());
    }

    @GetMapping("/authorizations/{request}")
    public McpOAuthService.Pending describe(@PathVariable String request) {
        return oauth.describe(request);
    }

    @PostMapping("/authorizations/{request}/approve")
    public McpOAuthService.Decision approve(@PathVariable String request) {
        return oauth.approve(request);
    }

    @PostMapping("/authorizations/{request}/deny")
    public McpOAuthService.Decision deny(@PathVariable String request) {
        return oauth.deny(request);
    }

    @GetMapping("/connections")
    public List<McpOAuthService.ConnectionView> connections() {
        return oauth.list();
    }

    @DeleteMapping("/connections/{id}")
    public void disconnect(@PathVariable UUID id) {
        oauth.disconnect(id);
    }
}
