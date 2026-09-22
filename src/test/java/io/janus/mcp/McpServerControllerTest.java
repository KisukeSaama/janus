package io.janus.mcp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class McpServerControllerTest {
    private final McpTools tools = Mockito.mock(McpTools.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new McpServerController(tools)).build();
        when(tools.exists("whoami")).thenReturn(true);
        when(tools.exists("delete_api")).thenReturn(true);
    }

    @Test
    void initializeAgreesOnAVersionTheClientAskedFor() throws Exception {
        rpc("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists())
                .andExpect(jsonPath("$.result.serverInfo.name").value("janus"))
                .andExpect(header().doesNotExist("Mcp-Session-Id"));
    }

    @Test
    void anUnknownVersionIsAnsweredWithTheNewest() throws Exception {
        rpc("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""")
                .andExpect(
                        jsonPath("$.result.protocolVersion").value(McpServerController.PROTOCOL_VERSIONS.getFirst()));
    }

    @Test
    void aNotificationIsAcknowledgedWithoutABody() throws Exception {
        rpc("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}""").andExpect(status().isAccepted()).andExpect(content().string(""));
    }

    @Test
    void toolsAreListed() throws Exception {
        when(tools.definitions()).thenReturn(List.of(Map.of("name", "whoami")));

        rpc("""
                {"jsonrpc":"2.0","id":"a","method":"tools/list"}""")
                .andExpect(jsonPath("$.id").value("a"))
                .andExpect(jsonPath("$.result.tools[0].name").value("whoami"));
    }

    @Test
    void aToolAnswersInTextBlocks() throws Exception {
        when(tools.call(eq("whoami"), any())).thenReturn(McpTools.Result.of("{\"role\":\"ADMIN\"}"));

        rpc("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"whoami","arguments":{}}}""")
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value("{\"role\":\"ADMIN\"}"));
    }

    /** The model reads a refusal and corrects itself; it is not a protocol failure. */
    @Test
    void aRefusalIsAToolResultMarkedAsAnError() throws Exception {
        when(tools.call(eq("delete_api"), any()))
                .thenThrow(new AccessDeniedException("Only administrators manage APIs"));

        rpc("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"delete_api","arguments":{"id":"x"}}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].text")
                        .value(org.hamcrest.Matchers.containsString("Only administrators")));
    }

    @Test
    void anUnexpectedFailureIsNotQuoted() throws Exception {
        when(tools.call(eq("whoami"), any())).thenThrow(new IllegalStateException("connection pool secret detail"));

        rpc("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"whoami"}}""")
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void anUnknownToolOrMethodIsAProtocolError() throws Exception {
        rpc("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"format_disk"}}""").andExpect(jsonPath("$.error.code").value(-32602));
        rpc("""
                {"jsonrpc":"2.0","id":6,"method":"resources/list"}""").andExpect(jsonPath("$.error.code").value(-32601));
    }

    @Test
    void batchesAndUnsupportedVersionsAreRefused() throws Exception {
        rpc("""
                [{"jsonrpc":"2.0","id":1,"method":"ping"}]""").andExpect(status().isBadRequest());
        mvc.perform(post("/mcp")
                        .contentType("application/json")
                        .header("MCP-Protocol-Version", "1999-01-01")
                        .content("""
                                {"jsonrpc":"2.0","id":1,"method":"ping"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thereIsNoStreamToOpen() throws Exception {
        mvc.perform(get("/mcp")).andExpect(status().isMethodNotAllowed());
    }

    private org.springframework.test.web.servlet.ResultActions rpc(String body) throws Exception {
        return mvc.perform(post("/mcp")
                .contentType("application/json")
                .accept("application/json", "text/event-stream")
                .content(body));
    }
}
