package io.github.mrgucci1.aiChatBuddy.mcp;

import org.json.simple.JSONObject;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Generic tools client. Supports stateless 2026 and initialize/session-based 2025 MCP. */
public final class McpClient implements AutoCloseable {
    private final McpServerConfig config;
    private final AtomicLong sequence = new AtomicLong();
    private volatile McpTransport transport;
    private volatile String version;
    private volatile boolean closed;
    private Map<String, JSONObject> schemas = Map.of();
    public McpClient(McpServerConfig config) { this.config = config; }
    public String version() { return version; }
    public McpServerConfig config() { return config; }
    private McpTransport newTransport() throws IOException {
        return config.transport().equals("stdio") ? new StdioMcpTransport(config) : new HttpMcpTransport(config);
    }
    private void connect() throws IOException {
        if (closed) throw new McpException("MCP client is closed");
        if (version != null) return;
        if (transport == null) transport = newTransport();
        String wanted = config.protocolVersion();
        if (wanted.equals("auto") || wanted.equals("2026-07-28")) {
            try {
                JSONObject result = rpc("server/discover", Json.obj(), "2026-07-28", Map.of(),
                        config.transport().equals("stdio") ? Duration.ofSeconds(Math.min(5, config.timeout().toSeconds())) : config.timeout());
                if (!(result.get("supportedVersions") instanceof List<?> supported) || !supported.contains("2026-07-28"))
                    throw new McpException("MCP discovery did not advertise a compatible version");
                version = "2026-07-28"; return;
            } catch (McpException e) {
                if (!wanted.equals("auto")) throw e;
                if (e.httpStatus() == 401 || e.httpStatus() == 403 || e.httpStatus() == 429 || e.httpStatus() >= 500) throw e;
                if (e.modernError()) {
                    if (e.rpcCode() != -32022 || e.data() == null || !(e.data().get("supported") instanceof List<?> supported)) throw e;
                    wanted = McpServerConfig.VERSIONS.stream().filter(v -> !v.equals("2026-07-28") && supported.contains(v)).findFirst()
                            .orElseThrow(() -> new McpException("No mutually supported MCP protocol version"));
                } else {
                    boolean legacySignal = e.rpcCode() == -32600 || e.rpcCode() == -32601 || e.rpcCode() == -32602 ||
                            Set.of(400, 404, 405).contains(e.httpStatus()) ||
                            (config.transport().equals("stdio") && e.getMessage().contains("timed out"));
                    if (!legacySignal) throw e;
                    wanted = "2025-11-25";
                }
                if (config.transport().equals("stdio")) { transport.close(); transport = newTransport(); }
            }
        }
        JSONObject result = rpc("initialize", Json.obj("protocolVersion", wanted, "capabilities", Json.obj(),
                "clientInfo", Json.obj("name", "AiChatBuddy", "version", "1.6.0-mcp")), null, Map.of(), config.timeout());
        String selected = Json.string(result.get("protocolVersion"), "");
        if (!McpServerConfig.VERSIONS.contains(selected) || selected.equals("2026-07-28") ||
            (config.transport().equals("streamable-http") && selected.equals("2024-11-05")))
            throw new McpException("Unsupported negotiated MCP version");
        transport.notify(Json.obj("jsonrpc", "2.0", "method", "notifications/initialized"), selected);
        version = selected;
    }
    @SuppressWarnings("unchecked")
    private JSONObject rpc(String method, JSONObject params, String selected, Map<String,String> headers, Duration timeout) throws IOException {
        JSONObject wireParams = Json.copy(params);
        if ("2026-07-28".equals(selected)) wireParams.put("_meta", Json.obj(
                "io.modelcontextprotocol/protocolVersion", selected,
                "io.modelcontextprotocol/clientInfo", Json.obj("name", "AiChatBuddy", "version", "1.6.0-mcp"),
                "io.modelcontextprotocol/clientCapabilities", Json.obj()));
        String id = "aichat-" + sequence.incrementAndGet();
        McpTransport active = transport;
        if (active == null) throw new McpException("MCP connection was closed");
        JSONObject reply = active.exchange(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", wireParams), selected, headers, timeout);
        if (!"2.0".equals(reply.get("jsonrpc"))) throw new McpException("Invalid JSON-RPC version");
        if (reply.get("error") instanceof JSONObject error) {
            int code = error.get("code") instanceof Number n ? n.intValue() : -32603;
            throw new McpException("MCP RPC error " + code, 0, code, error.get("data") instanceof JSONObject d ? d : null);
        }
        if (!id.equals(reply.get("id"))) throw new McpException("MCP response ID mismatch");
        JSONObject result = Json.object(reply.get("result"));
        String resultType = Json.string(result.get("resultType"), "complete");
        if (!resultType.equals("complete")) throw new McpException("MCP result requires an unsupported client capability (" + Json.safeName(resultType) + ")");
        return result;
    }
    public synchronized List<JSONObject> listTools() throws IOException {
        try {
            connect();
            List<JSONObject> result = new ArrayList<>(); Set<String> cursors = new HashSet<>();
            Map<String,JSONObject> found = new LinkedHashMap<>(); String cursor = null;
            for (int page = 0; page < 32; page++) {
                JSONObject response = rpc("tools/list", cursor == null ? Json.obj() : Json.obj("cursor", cursor), version, Map.of(), config.timeout());
                if (!(response.get("tools") instanceof List<?> tools)) throw new McpException("MCP tools/list did not return tools");
                for (Object raw : tools) {
                    JSONObject tool = Json.object(raw);
                    if (!(tool.get("name") instanceof String name) || name.isBlank() || name.length() > 512)
                        throw new McpException("MCP tool has an invalid name");
                    if (!(tool.get("inputSchema") instanceof JSONObject schema)) continue;
                    if (tool.get("execution") instanceof JSONObject execution && "required".equals(execution.get("taskSupport"))) continue;
                    try { if (config.transport().equals("streamable-http")) McpHeaders.bindings(schema); }
                    catch (IOException invalid) { continue; }
                    if (found.putIfAbsent(name, Json.copy(schema)) != null) throw new McpException("Duplicate MCP tool name");
                    result.add(Json.copy(tool));
                    if (found.size() > 2048) throw new McpException("MCP tool catalogue is too large");
                }
                cursor = Json.string(response.get("nextCursor"), "");
                if (cursor.isEmpty()) { schemas = Map.copyOf(found); return List.copyOf(result); }
                if (!cursors.add(cursor)) throw new McpException("MCP tool pagination cycle");
            }
            throw new McpException("MCP tool pagination exceeded 32 pages");
        } catch (IOException e) { invalidate(); throw e; }
    }
    public synchronized JSONObject callTool(String name, JSONObject arguments) throws IOException {
        try {
            connect();
            JSONObject schema = schemas.get(name);
            Map<String,String> headers = "2026-07-28".equals(version) && schema != null && config.transport().equals("streamable-http")
                    ? McpHeaders.extract(McpHeaders.bindings(schema), arguments) : Map.of();
            return rpc("tools/call", Json.obj("name", name, "arguments", arguments), version, headers, config.timeout());
        } catch (IOException e) {
            // A timed-out or disconnected tools/call may have executed. NEVER automatically replay it.
            if (!(e instanceof McpException m) || m.rpcCode() == 0) invalidate();
            throw e;
        }
    }
    private void invalidate() {
        version = null;
        McpTransport old = transport; transport = null;
        if (old != null) old.close();
    }
    @Override public void close() { closed = true; invalidate(); }
}
