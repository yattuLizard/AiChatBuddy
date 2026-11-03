package io.github.mrgucci1.aiChatBuddy.mcp;

import org.junit.jupiter.api.Test;
import org.json.simple.JSONObject;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class McpClientTest {
    @Test void modernDiscoveryListCallAndHeaders() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            assertEquals(1, client.listTools().size());
            assertEquals("2026-07-28", client.version());
            JSONObject result = client.callTool("bm_vehicles", Json.obj("loaded_only", false, "limit", 1));
            assertEquals(33L, ((JSONObject) result.get("structuredContent")).get("total"));
            assertEquals(1, fixture.calls.get()); assertTrue(fixture.failures.isEmpty(), fixture.failures.toString());
        }
    }
    @Test void legacyInitializationSessionAndVersionNegotiation() throws Exception {
        try (var fixture = new TestMcpServer()) {
            fixture.mode = "legacy";
            try (var client = new McpClient(fixture.config(Map.of()))) {
                client.listTools(); client.callTool("bm_vehicles", Json.obj());
                assertEquals("2025-06-18", client.version());
                assertTrue(fixture.requests.stream().anyMatch(r -> "notifications/initialized".equals(r.get("method"))));
                assertTrue(fixture.failures.isEmpty(), fixture.failures.toString());
            }
        }
    }
    @Test void sseNotificationsIgnoredAndFinalResponseReadBeforeStreamCloses() throws Exception {
        try (var fixture = new TestMcpServer()) {
            fixture.mode = "sse"; fixture.holdMillis = 3000;
            try (var client = new McpClient(fixture.config(Map.of()))) {
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(1, client.listTools().size()));
            }
        }
    }
    @Test void paginationCollectsAllPages() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            fixture.pagination = true; assertEquals(2, client.listTools().size());
        }
    }
    @Test void paginationCyclesRejected() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            fixture.pagination = true; fixture.cycle = true;
            assertThrows(IOException.class, client::listTools);
        }
    }
    @Test void authenticationFailureDoesNotFallbackOrExposeBody() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of("token", "bad")))) {
            IOException error = assertThrows(IOException.class, client::listTools);
            assertTrue(error.getMessage().contains("401")); assertFalse(error.getMessage().contains("DO_NOT_LEAK"));
            assertEquals(1, fixture.requests.size());
        }
    }
    @Test void expiredCallIsNeverReplayed() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            client.listTools(); fixture.mode = "expired";
            assertThrows(IOException.class, () -> client.callTool("bm_vehicles", Json.obj()));
            assertEquals(1, fixture.calls.get());
        }
    }
    @Test void malformedResponseRejected() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            fixture.mode = "malformed"; assertThrows(IOException.class, client::listTools);
        }
    }
    @Test void responseSizeLimitEnforced() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of("max-response-bytes", 2048)))) {
            fixture.mode = "oversized";
            assertThrows(IOException.class, client::listTools);
        }
    }
    @Test void responseBodyTimeoutEnforced() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of("timeout-seconds", 1)))) {
            fixture.mode = "hang"; fixture.holdMillis = 4000;
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertThrows(IOException.class, client::listTools));
        }
    }
    @Test void mcpErrorIsReturnedAsToolResultNotThrown() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            var tools = client.listTools(); fixture.toolError = true;
            var tool = new McpAgentTool(client, tools.getFirst(), Logger.getAnonymousLogger());
            assertEquals(Boolean.TRUE, Json.parse(tool.execute(Json.obj())).get("isError"));
        }
    }
    @Test void networkErrorIsReturnedAsToolResult() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            var tool = new McpAgentTool(client, client.listTools().getFirst(), Logger.getAnonymousLogger());
            fixture.close();
            assertEquals(Boolean.TRUE, Json.parse(tool.execute(Json.obj())).get("isError"));
        }
    }
    @Test void modernHeaderParametersSupportUnicodeAndNestedValues() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            fixture.schema = Json.obj("type", "object", "properties", Json.obj("location", Json.obj("type", "object", "properties", Json.obj(
                    "name", Json.obj("type", "string", "x-mcp-header", "Location")))));
            client.listTools(); client.callTool("bm_vehicles", Json.obj("location", Json.obj("name", "東京")));
            Map<String,List<String>> h = fixture.headers.getLast();
            assertEquals(List.of(McpHeaders.encode("東京")), h.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Mcp-Param-Location")).findFirst().orElseThrow().getValue());
        }
    }
    @Test void invalidHeaderSchemaToolIsNotAdvertised() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            fixture.schema = Json.obj("type", "object", "properties", Json.obj("v", Json.obj("type","string","x-mcp-header","bad\r\nname")));
            assertTrue(client.listTools().isEmpty());
        }
    }
    @Test void closedClientDoesNotReconnect() throws Exception {
        try (var fixture = new TestMcpServer(); var client = new McpClient(fixture.config(Map.of()))) {
            client.close(); assertThrows(IOException.class, client::listTools); assertTrue(fixture.requests.isEmpty());
        }
    }
    @Test void stdioModernAndLegacySubprocesses() throws Exception {
        for (String era : List.of("modern", "legacy")) {
            String cp = String.join(File.pathSeparator,
                    new File(StdioFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                    new File(McpClient.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                    new File(JSONObject.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            var config = McpServerConfig.from("local", Map.of("transport", "stdio", "command", System.getProperty("java.home") + "/bin/java",
                    "args", List.of("-cp", cp, StdioFixture.class.getName(), era), "timeout-seconds", 3));
            try (var client = new McpClient(config)) {
                assertEquals(1, client.listTools().size());
                assertEquals(false, client.callTool("echo", Json.obj()).get("isError"));
                assertEquals(era.equals("modern") ? "2026-07-28" : "2025-11-25", client.version());
            }
        }
    }
}
