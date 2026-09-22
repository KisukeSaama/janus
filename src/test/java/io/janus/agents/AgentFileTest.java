package io.janus.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class AgentFileTest {
    private static AgentFile.Api api(String name, String slug) {
        return new AgentFile.Api(name, slug, false, null, List.of(), false, null, List.of(), List.of());
    }

    @Test
    void thePlaceholderTellsTheAgentToAskFirst() {
        var file = AgentFile.render(AgentFile.placeholder("https://janus.example.com"));

        assertThat(file)
                .startsWith("# Janus gateway\n")
                .contains("JANUS_URL=https://janus.example.com")
                .contains("None yet. Follow the next section before writing any call.")
                .contains("**Registry → Applications**: on this service,")
                .doesNotContain("%");
    }

    @Test
    void aServiceIsNamedAndItsApisListed() {
        var file = AgentFile.render(new AgentFile.Target(
                "https://janus.example.com",
                "orders",
                "0f1e2d3c-0000-0000-0000-000000000000",
                List.of(api("Spotify", "spotify"))));

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
        var plain = AgentFile.render(AgentFile.placeholder("https://j"));
        assertThat(plain).doesNotContain("APIs marked **JSON**").doesNotContain("APIs marked **GraphQL**");

        var converted = new AgentFile.Api("Plex", "plex", true, null, List.of(), false, null, List.of(), List.of());
        assertThat(AgentFile.conversionNote(List.of(converted))).contains("APIs marked **JSON**");
    }
}
