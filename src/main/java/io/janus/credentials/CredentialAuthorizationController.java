package io.janus.credentials;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The two ends of an authorisation: the console starting one, and the provider sending somebody back.
 *
 * <p>They are deliberately different surfaces. Starting one is an authenticated action taken by an
 * operator in the console. The return is a browser arriving from another site, carrying no session
 * cookie — {@code SameSite=strict} sees to that — and trusted on the state it quotes and nothing else.
 * Putting the second behind authentication would not make it safer; it would make it impossible.
 */
@RestController
public class CredentialAuthorizationController {
    private static final Logger log = LoggerFactory.getLogger(CredentialAuthorizationController.class);

    /** Hosts that mean "nobody has said where this deployment lives yet". */
    private static final java.util.Set<String> LOOPBACK = java.util.Set.of("localhost", "127.0.0.1", "::1");

    private final CredentialAuthorizationService authorizations;
    private final String consoleUrl;

    public CredentialAuthorizationController(
            CredentialAuthorizationService authorizations,
            @Value("${janus.console-url:${janus.public-url:http://localhost:8080}}") String consoleUrl) {
        this.authorizations = authorizations;
        this.consoleUrl = consoleUrl.endsWith("/") ? consoleUrl.substring(0, consoleUrl.length() - 1) : consoleUrl;
    }

    /**
     * Where to send the operator so the account holder can agree.
     *
     * <p>A POST because it writes: a pending authorisation is recorded before the address exists, and
     * the address is only meaningful because that row does.
     */
    @PostMapping("/api/admin/credentials/{id}/authorization")
    public CredentialAuthorizationService.Started start(@PathVariable UUID id) {
        return authorizations.start(id);
    }

    /** Forgets a stored consent. The provider's own revocation stays with the person who granted it. */
    @DeleteMapping("/api/admin/credentials/{id}/authorization")
    public void revoke(@PathVariable UUID id) {
        authorizations.revoke(id);
    }

    /**
     * The address every provider has to be told to send people back to.
     *
     * <p>It exists as an endpoint because it cannot be worked out from the console: the console may be
     * served from another origin entirely, and what matters is where Janus itself is reachable. An
     * operator who has to guess this registers the wrong one and meets a refusal that names nothing.
     *
     * @param configured false when the deployment never set its public URL, so the address below is
     *     the built-in localhost default and will be refused by every provider it is registered with
     */
    @GetMapping("/api/admin/oauth/callback")
    public Callback callback() {
        var uri = authorizations.callbackUri();
        return new Callback(uri.toString(), !LOOPBACK.contains(uri.getHost()));
    }

    /** @see #callback() */
    public record Callback(String url, boolean configured) {}

    /**
     * Where providers send the browser back.
     *
     * <p>Answers with a redirect rather than a body, because the audience is a person looking at a tab
     * they were sent away from, not a program. Everything that could have gone wrong here — a refused
     * consent, a stale state, a provider that issued no refresh token — ends up as a message on the
     * page they came from.
     */
    @GetMapping(CredentialAuthorizationService.CALLBACK_PATH)
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {
        // The provider says the person declined, or that the request was malformed. Its own wording is
        // not carried: it is written for a developer reading a spec, in a language nobody chose here.
        if (error != null) return back(null, "declined");
        if (code == null || state == null) return back(null, "incomplete");

        try {
            var done = authorizations.complete(state, code);
            return back(done.providerName(), null);
        } catch (RuntimeException ex) {
            // The message is written for the operator and carries nothing from the provider's body.
            log.warn("An authorisation could not be completed: {}", ex.getMessage());
            return back(null, ex.getMessage());
        }
    }

    /** Back to the console, with just enough for it to say what happened. */
    private ResponseEntity<Void> back(String authorized, String failed) {
        var url = UriComponentsBuilder.fromUriString(consoleUrl + "/connections");
        if (authorized != null) url.queryParam("authorized", encode(authorized));
        if (failed != null) url.queryParam("authorizationFailed", encode(failed));
        return ResponseEntity.status(302)
                .location(URI.create(url.build().toUriString()))
                .build();
    }

    private static String encode(String value) {
        return org.springframework.web.util.UriUtils.encode(value, StandardCharsets.UTF_8);
    }
}
