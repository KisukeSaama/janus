package io.janus.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.*;

import jakarta.validation.Validation;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import io.janus.accounts.*;
import io.janus.agents.AgentFileService;
import io.janus.applications.*;
import io.janus.credentials.CredentialService;
import io.janus.grants.GrantRequest;
import io.janus.grants.GrantResponse;
import io.janus.grants.GrantService;
import io.janus.providers.ProviderCapabilities;
import io.janus.providers.ProviderService;

class McpToolsTest {
    private final AccessScope scope = Mockito.mock(AccessScope.class);
    private final ProviderService providers = Mockito.mock(ProviderService.class);
    private final ApplicationService applications = Mockito.mock(ApplicationService.class);
    private final CredentialService credentials = Mockito.mock(CredentialService.class);
    private final GrantService grants = Mockito.mock(GrantService.class);
    private final AgentFileService agentFiles = Mockito.mock(AgentFileService.class);

    private McpTools tools;

    @BeforeEach
    void setUp() {
        tools = new McpTools(
                new ObjectMapper(),
                Validation.buildDefaultValidatorFactory().getValidator(),
                scope,
                providers,
                applications,
                credentials,
                grants,
                agentFiles);
    }

    @Test
    void everyAdvertisedToolHasASchemaAndAnnotations() {
        var names = tools.definitions().stream().map(tool -> tool.get("name")).toList();

        assertThat(names).contains("whoami", "create_api", "update_api", "create_app", "create_grant", "get_janus_md");
        for (var tool : tools.definitions()) {
            assertThat(tool).containsKeys("name", "description", "inputSchema", "annotations");
            assertThat(((Map<?, ?>) tool.get("inputSchema")).get("type")).isEqualTo("object");
        }
    }

    @Test
    void anUpdateToolTakesItsCreateShapeWithOnlyTheIdRequired() {
        var update = definition("update_api");
        var create = definition("create_api");

        assertThat(schema(update).get("required")).isEqualTo(List.of("id"));
        assertThat(properties(update).keySet()).containsAll(properties(create).keySet());
        assertThat(schema(create).get("required")).isEqualTo(List.of("name", "slug", "baseUrl", "authType"));
    }

    @Test
    void destructiveToolsSayTheyAre() {
        assertThat(annotations("delete_api").get("destructiveHint")).isEqualTo(true);
        assertThat(annotations("list_apis").get("readOnlyHint")).isEqualTo(true);
        assertThat(annotations("create_app").get("readOnlyHint")).isEqualTo(false);
    }

    @Test
    void creatingAnAppNeverHandsTheKeyToTheAssistant() {
        var app = app(UUID.randomUUID(), "orders");
        when(applications.create(any())).thenReturn(new IssuedApplication(app, "jns_secret-value"));

        var result = tools.call("create_app", Map.of("name", "orders"));

        assertThat(String.join("", result.text()))
                .doesNotContain("jns_secret-value")
                .contains("Rotate key");
        var request = ArgumentCaptor.forClass(ApplicationRequest.class);
        verify(applications).create(request.capture());
        assertThat(request.getValue().enabled()).isTrue();
    }

    @Test
    void anUpdateIsAPatchOverWhatIsStored() {
        var id = UUID.randomUUID();
        var stored = new ApplicationResponse(
                id, "orders", "Orders service", true, List.of("https://a.example.com"), null, Instant.now(), null);
        when(applications.list()).thenReturn(List.of(stored));
        when(applications.update(eq(id), any())).thenReturn(stored);

        tools.call("update_app", Map.of("id", id.toString(), "enabled", false));

        var request = ArgumentCaptor.forClass(ApplicationRequest.class);
        verify(applications).update(eq(id), request.capture());
        assertThat(request.getValue().name()).isEqualTo("orders");
        assertThat(request.getValue().description()).isEqualTo("Orders service");
        assertThat(request.getValue().allowedOrigins()).containsExactly("https://a.example.com");
        assertThat(request.getValue().enabled()).isFalse();
    }

    @Test
    void aGrantPatchKeepsItsScope() {
        var id = UUID.randomUUID();
        var stored = new GrantResponse(
                id,
                UUID.randomUUID(),
                "orders",
                UUID.randomUUID(),
                "Spotify",
                UUID.randomUUID(),
                "key",
                true,
                60,
                0,
                "/v1/albums",
                List.of("GET"),
                true,
                List.of(),
                List.of(),
                Instant.now(),
                null);
        when(grants.list()).thenReturn(List.of(stored));
        when(grants.update(eq(id), any())).thenReturn(stored);

        tools.call("update_grant", Map.of("id", id.toString(), "rateLimitPerMinute", 120));

        var request = ArgumentCaptor.forClass(GrantRequest.class);
        verify(grants).update(eq(id), request.capture());
        assertThat(request.getValue().rateLimitPerMinute()).isEqualTo(120);
        assertThat(request.getValue().pathPrefix()).isEqualTo("/v1/albums");
        assertThat(request.getValue().methods()).containsExactly("GET");
        assertThat(request.getValue().applicationId()).isEqualTo(stored.applicationId());
    }

    @Test
    void aMisspelledFieldIsRefusedRatherThanIgnored() {
        assertThatThrownBy(() -> tools.call("create_app", Map.of("name", "orders", "enabeld", true)))
                .isInstanceOf(McpTools.ToolError.class)
                .hasMessageContaining("enabeld");
        verifyNoInteractions(applications);
    }

    @Test
    void argumentsAreValidatedLikeTheConsoleValidatesAForm() {
        assertThatThrownBy(() -> tools.call("create_app", Map.of("name", "")))
                .isInstanceOf(McpTools.ToolError.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> tools.call(
                        "create_api",
                        Map.of("name", "Spotify", "slug", "Not A Slug", "baseUrl", "x", "authType", "NONE")))
                .isInstanceOf(McpTools.ToolError.class)
                .hasMessageContaining("slug");
    }

    @Test
    void anUnusableValueNamesTheField() {
        assertThatThrownBy(() -> tools.call(
                        "create_api",
                        Map.of("name", "Spotify", "slug", "spotify", "baseUrl", "x", "authType", "TELEPATHY")))
                .isInstanceOf(McpTools.ToolError.class)
                .hasMessageContaining("authType");
    }

    @Test
    void whoamiSaysWhetherTheCatalogueIsTheirsToChange() {
        var user = new ConsoleUser(TestAccount.owner());
        when(scope.current()).thenReturn(user);
        when(scope.assistant()).thenReturn(Optional.of(new ActingAssistant(UUID.randomUUID(), "Claude Code")));
        when(providers.capabilities()).thenReturn(new ProviderCapabilities(false));

        var text = tools.call("whoami", Map.of()).text().getFirst();

        assertThat(text).contains("\"role\":\"USER\"", "\"managesApiCatalogue\":false", "Claude Code");
    }

    @Test
    void theAgentFileArrivesAsItsOwnBlock() {
        var id = UUID.randomUUID();
        when(agentFiles.render(id)).thenReturn(new AgentFileService.Rendered("JANUS.md", "# Janus gateway\n", 2));

        var result = tools.call("get_janus_md", Map.of("applicationId", id.toString()));

        assertThat(result.text()).hasSize(2);
        assertThat(result.text().getFirst()).contains("JANUS.md", "2 API(s)");
        assertThat(result.text().get(1)).isEqualTo("# Janus gateway\n");
    }

    @Test
    void anIdMustBeAnId() {
        assertThatThrownBy(() -> tools.call("delete_api", Map.of("id", "spotify")))
                .isInstanceOf(McpTools.ToolError.class)
                .hasMessageContaining("UUID");
        assertThatThrownBy(() -> tools.call("get_api", Map.of())).isInstanceOf(McpTools.ToolError.class);
    }

    private static ApplicationResponse app(UUID id, String name) {
        return new ApplicationResponse(id, name, null, true, List.of(), Instant.now(), Instant.now(), Instant.now());
    }

    private Map<String, Object> definition(String name) {
        return tools.definitions().stream()
                .filter(tool -> tool.get("name").equals(name))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("inputSchema");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> tool) {
        return (Map<String, Object>) schema(tool).get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> annotations(String name) {
        return (Map<String, Object>) definition(name).get("annotations");
    }
}
