package io.github.mrgucci1.aiChatBuddy.mcp;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class McpPolicyTest {
    private McpServerConfig config(Map<String,Object> v) {
        Map<String,Object> map = new HashMap<>(v); map.putIfAbsent("url", "http://127.0.0.1:8765/mcp");
        return McpServerConfig.from("bluemap", map);
    }
    @Test void missingEnvironmentVariableFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> McpServerConfig.from("x", Map.of("url","https://example.com/mcp","token","${MISSING}"), n -> null));
    }
    @Test void tokenEnvironmentExpansionAndRedactedToString() {
        var c = McpServerConfig.from("x", Map.of("url","https://example.com/mcp","token","${SECRET}"), n -> "never-log-me");
        assertEquals("never-log-me", c.token()); assertFalse(c.toString().contains("never-log-me"));
    }
    @Test void privateHttpRequiresExplicitOptIn() {
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("url", "http://bluemap-mcp:8765/mcp")));
        assertNotNull(config(Map.of("url", "http://bluemap-mcp:8765/mcp", "allow-insecure-http", true)));
    }
    @Test void headerInjectionAndReservedHeadersRejected() {
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("token", "bad\r\nheader")));
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("headers", Map.of("MCP-Protocol-Version", "bad"))));
    }
    @Test void credentialsInUrlRejected() {
        assertThrows(IllegalArgumentException.class, () -> config(Map.of("url", "https://user:password@example.com/mcp")));
    }
    @Test void remoteReadOnlyHintNotTrustedByDefault() {
        var tool = TestMcpServer.tool("danger", Json.obj("type", "object"));
        tool.put("annotations", Json.obj("readOnlyHint", true));
        assertFalse(McpAgentTool.allowed(config(Map.of()), tool));
        assertTrue(McpAgentTool.allowed(config(Map.of("trust-read-only-hints", true)), tool));
    }
    @Test void explicitReadonlyAttestationAndDenyPrecedence() {
        var tool = TestMcpServer.tool("bm_vehicles", Json.obj("type","object"));
        assertTrue(McpAgentTool.allowed(config(Map.of("read-only-tools", List.of("bm_vehicles"))), tool));
        assertFalse(McpAgentTool.allowed(config(Map.of("read-only-tools", List.of("bm_*"), "deny-tools", List.of("bm_vehicles"))), tool));
    }
    @Test void writesRequireBothFlagAndAllowlist() {
        var tool = TestMcpServer.tool("delete", Json.obj("type","object"));
        assertFalse(McpAgentTool.allowed(config(Map.of("read-only", false)), tool));
        assertFalse(McpAgentTool.allowed(config(Map.of("allow-tools", List.of("delete"))), tool));
        assertTrue(McpAgentTool.allowed(config(Map.of("read-only", false, "allow-tools", List.of("delete"))), tool));
    }
    @Test void aliasesAreUniqueStableAndProviderSafe() {
        String a = McpAgentTool.alias("srv", "a-b"); String b = McpAgentTool.alias("srv", "a_b");
        assertNotEquals(a,b); assertEquals(a, McpAgentTool.alias("srv", "a-b"));
        assertTrue(McpAgentTool.alias("srv", "あ".repeat(100)).matches("[A-Za-z0-9_]{1,64}"));
        assertEquals("mcp_bluemap_bm_vehicles", McpAgentTool.alias("bluemap", "bm_vehicles"));
    }
    @Test void permissionIsCheckedOnDiscoveryAndAgainBeforeExecution() throws Exception {
        try (var f = new TestMcpServer(); var r = new McpRegistry(List.of(f.config(Map.of())), 48, Logger.getAnonymousLogger())) {
            r.refresh().get();
            assertTrue(r.toolsFor(p -> false, p -> true).isEmpty());
            var tool = r.toolsFor(p -> true, p -> false).getFirst();
            assertEquals(true, Json.parse(tool.execute(Json.obj())).get("isError"));
            assertEquals(0, f.calls.get());
        }
    }
    @Test void failedServerDoesNotHideHealthyServers() throws Exception {
        try (var f = new TestMcpServer(); var bad = new TestMcpServer()) {
            bad.expectedToken = "different";
            var a = f.config(Map.of());
            var b = McpServerConfig.from("bad", Map.of("url",bad.url(),"token","secret","read-only-tools",List.of("bm_vehicles")));
            try (var r = new McpRegistry(List.of(a,b), 48, Logger.getAnonymousLogger())) {
                r.refresh().get(); assertEquals(1, r.toolsFor(p -> true, p -> true).size());
                assertTrue(r.statusLines().stream().anyMatch(line -> line.contains("unavailable")));
            }
        }
    }
    @Test void unicodeAndSentinelHeaderValuesAreEncoded() {
        assertEquals("ascii", McpHeaders.encode("ascii"));
        assertTrue(McpHeaders.encode("日本語").startsWith("=?base64?"));
        assertNotEquals("=?base64?literal?=", McpHeaders.encode("=?base64?literal?="));
        assertTrue(McpHeaders.encode(" bad ").startsWith("=?base64?"));
    }
    @Test void headerAnnotationsUnderUnionOrArrayAreRejected() {
        var annotation = Json.obj("type","string","x-mcp-header","X");
        assertThrows(java.io.IOException.class, () -> McpHeaders.bindings(Json.obj("type","object","anyOf", Json.arr(annotation))));
        assertThrows(java.io.IOException.class, () -> McpHeaders.bindings(Json.obj("type","object","properties",Json.obj("list",Json.obj("type","array","items",annotation)))));
    }
    @Test void openApiFallbackResolvesNullableReference() {
        var schema = Json.obj("type","object","$defs",Json.obj("X", Json.obj("type","string")), "properties",Json.obj("v",Json.obj("anyOf",Json.arr(Json.obj("$ref","#/$defs/X"),Json.obj("type","null")))));
        var converted = SchemaAdapter.toOpenApi(schema);
        assertFalse(converted.containsKey("$defs"));
        assertTrue(converted.toJSONString().contains("nullable"));
    }
}
