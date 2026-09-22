package io.janus.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class AgentFileTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    private static AgentFile.Api api(String name, String slug) {
        return new AgentFile.Api(name, slug, false, null, List.of(), false, null, List.of(), List.of());
    }

    @Test
    void thePlaceholderTellsTheAgentToAskFirst() {
        var file = AgentFile.render(AgentFile.placeholder("https://janus.example.com", DAY));

        assertThat(file)
                .startsWith("# Janus gateway\n")
                .contains("JANUS_URL=https://janus.example.com")
                .contains("None yet. Follow the next section before writing any call.")
                .contains("**Registry → Applications**: on this service,")
                .doesNotContain("%");
    }

    /**
     * The two halves of not being trusted blindly: the file says when it was true, and it says what to
     * do about a refusal it did not predict — which is never to route around it.
     */
    @Test
    void theFileIsDatedAndSaysWhatAStaleOneLooksLike() {
        var file = AgentFile.render(AgentFile.placeholder("https://janus.example.com", DAY));

        assertThat(file)
                .contains("Written 2026-09-22, and true that day.")
                .contains("403 `grant_missing`")
                .contains("Do not work around")
                .contains("do not edit the list by hand")
                .doesNotContain("add the new slug to this file");
    }

    /**
     * An agent connected to Janus over MCP is told it may register the API itself; one that is not is
     * told to ask. The old text only knew the second case, and contradicted the tools the assistant
     * was holding.
     */
    @Test
    void anUnlistedApiNamesBothWaysToGetIt() {
        var file = AgentFile.render(AgentFile.placeholder("https://janus.example.com", DAY));

        assertThat(file)
                .contains("`https://janus.example.com/mcp`")
                .contains("`create_grant`")
                .contains("Without those tools, ask the operator.")
                .contains("never ask anyone for its key");
    }

    @Test
    void aServiceIsNamedAndItsApisListed() {
        var file = AgentFile.render(new AgentFile.Target(
                "https://janus.example.com",
                "orders",
                "0f1e2d3c-0000-0000-0000-000000000000",
                List.of(api("Spotify", "spotify")),
                DAY));

        assertThat(file)
                .contains("JANUS_APPLICATION_ID=0f1e2d3c-0000-0000-0000-000000000000   # this service (orders)")
                .contains("- **Spotify**: `/gateway/spotify/…`\n")
                .contains("on `orders`,");
    }

    @Test
    void aNarrowedGrantIsWrittenAsTheCeilingItIs() {
        var line = AgentFile.apiList(List.of(new AgentFile.Api(
                "GitHub",
                "github",
                true,
                "/repos",
                List.of("GET", "HEAD"),
                true,
                "/graphql",
                List.of("QUERY"),
                List.of("viewer"))));

        assertThat(line)
                .isEqualTo("- **GitHub**: `/gateway/github/…` (**JSON**, only `/repos` and under, only GET, HEAD, "
                        + "**GraphQL** at `/graphql`, only query, root fields viewer, app **and** account)");
    }

    @Test
    void notesAppearOnlyWhenSomethingCallsForThem() {
        var plain = AgentFile.render(AgentFile.placeholder("https://j", DAY));
        assertThat(plain).doesNotContain("APIs marked **JSON**").doesNotContain("APIs marked **GraphQL**");

        var converted = new AgentFile.Api("Plex", "plex", true, null, List.of(), false, null, List.of(), List.of());
        assertThat(AgentFile.conversionNote(List.of(converted))).contains("APIs marked **JSON**");
    }
}
