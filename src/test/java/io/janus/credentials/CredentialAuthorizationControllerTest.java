package io.janus.credentials;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.janus.shared.NotFoundException;

/**
 * What a browser meets when it comes back from a provider.
 *
 * <p>Every outcome here ends as a redirect to the console, because the audience is a person looking
 * at a tab they were sent away from. The two things asserted throughout: that nothing the provider
 * said travels into the address, and that a failure is still a way back rather than an error page.
 */
class CredentialAuthorizationControllerTest {
    private final CredentialAuthorizationService authorizations = mock(CredentialAuthorizationService.class);
    private final CredentialAuthorizationController controller =
            new CredentialAuthorizationController(authorizations, "https://console.example.com/");

    private String locationOf(org.springframework.http.ResponseEntity<Void> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(302);
        return response.getHeaders().getFirst(HttpHeaders.LOCATION);
    }

    @Test
    void sends_the_reader_back_naming_the_connection_that_is_now_live() {
        when(authorizations.complete("state-1", "code-1"))
                .thenReturn(new CredentialAuthorizationService.Completed(UUID.randomUUID(), "Spotify", "a@b.c"));

        assertThat(locationOf(controller.callback("code-1", "state-1", null)))
                .isEqualTo("https://console.example.com/connections?authorized=Spotify");
    }

    @Test
    void says_it_was_declined_without_repeating_what_the_provider_called_it() {
        // Providers send things like "access_denied" or a sentence in a language nobody chose here.
        var location = locationOf(controller.callback(null, "state-1", "access_denied"));

        assertThat(location).contains("authorizationFailed=declined").doesNotContain("access_denied");
        verifyNoInteractions(authorizations);
    }

    @Test
    void does_not_try_to_exchange_half_a_callback() {
        assertThat(locationOf(controller.callback(null, "state-1", null))).contains("authorizationFailed=incomplete");
        assertThat(locationOf(controller.callback("code-1", null, null))).contains("authorizationFailed=incomplete");
        verifyNoInteractions(authorizations);
    }

    @Test
    void carries_a_refusal_back_as_something_the_reader_can_act_on() {
        when(authorizations.complete(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("This authorisation is no longer valid."));

        assertThat(locationOf(controller.callback("code-1", "stale", null)))
                .startsWith("https://console.example.com/connections?authorizationFailed=")
                .contains("no%20longer%20valid");
    }

    /** A credential deleted while somebody was consenting. Still a way back, not a stack trace. */
    @Test
    void survives_a_connection_that_disappeared_underneath_the_flow() {
        when(authorizations.complete(anyString(), anyString()))
                .thenThrow(new NotFoundException("Credential not found"));

        assertThat(locationOf(controller.callback("code-1", "state-1", null))).contains("authorizationFailed=");
    }

    @Test
    void starts_and_revokes_through_the_service_that_owns_the_decision() {
        var id = UUID.randomUUID();
        when(authorizations.start(id))
                .thenReturn(new CredentialAuthorizationService.Started("https://x/authorize", "X"));

        assertThat(controller.start(id).authorizationUrl()).isEqualTo("https://x/authorize");

        controller.revoke(id);
        verify(authorizations).revoke(id);
    }
}
