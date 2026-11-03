package io.github.mrgucci1.aiChatBuddy.mcp;

import io.github.mrgucci1.aiChatBuddy.tools.AgentTool;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.logging.Logger;
import org.json.simple.JSONObject;

/** Isolates each configured server. Network discovery is NEVER performed on the Minecraft thread. */
public final class McpRegistry implements AutoCloseable {
    private static final class Entry {
        final McpClient client;
        volatile List<McpAgentTool> tools = List.of();
        volatile String status = "connecting";
        Entry(McpServerConfig config) { client = new McpClient(config); }
    }
    private final List<Entry> entries;
    private final Logger logger;
    private final ExecutorService workers = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "aichat-mcp-discovery"); t.setDaemon(true); return t;
    });
    private final int maxTotalTools;
    private volatile boolean closed;
    private CompletableFuture<Void> refreshing;
    public McpRegistry(List<McpServerConfig> configs, int maxTotalTools, Logger logger) {
        if (configs.size() > 16) throw new IllegalArgumentException("At most 16 MCP servers are supported per plugin");
        this.entries = configs.stream().map(Entry::new).toList();
        this.logger = logger; this.maxTotalTools = Math.max(1, Math.min(128, maxTotalTools));
    }
    public synchronized CompletableFuture<Void> refresh() {
        if (closed) return CompletableFuture.completedFuture(null);
        if (refreshing != null && !refreshing.isDone()) return refreshing;
        refreshing = CompletableFuture.allOf(entries.stream().map(e -> CompletableFuture.runAsync(() -> {
            if (closed) return;
            try {
                List<JSONObject> catalogue = e.client.listTools();
                List<McpAgentTool> admitted = catalogue.stream().filter(t -> McpAgentTool.allowed(e.client.config(), t))
                        .limit(e.client.config().maxTools()).map(t -> new McpAgentTool(e.client, t, logger)).toList();
                if (closed) { e.client.close(); return; }
                e.tools = admitted;
                e.status = "connected protocol=" + e.client.version() + " tools=" + admitted.size() + "/" + catalogue.size();
                logger.info("[MCP] server=" + e.client.config().id() + " " + e.status);
            } catch (Exception failure) {
                e.tools = List.of();
                e.status = "unavailable: " + (failure instanceof McpException ? failure.getMessage() : failure.getClass().getSimpleName());
                logger.warning("[MCP] server=" + e.client.config().id() + " " + e.status);
            }
        }, workers)).toArray(CompletableFuture[]::new));
        return refreshing;
    }
    public List<AgentTool> toolsFor(Predicate<String> hasPermission, Predicate<String> livePermissionCheck) {
        if (closed) return List.of();
        List<AgentTool> result = new ArrayList<>(); Set<String> aliases = new HashSet<>();
        for (Entry e : entries) for (McpAgentTool tool : e.tools) {
            if (!permitted(tool, hasPermission) || result.size() >= maxTotalTools) continue;
            if (!aliases.add(tool.name())) { logger.warning("[MCP] Duplicate tool alias was hidden: " + tool.name()); continue; }
            result.add(new AgentTool() {
                @Override public String name() { return tool.name(); }
                @Override public String description() { return tool.description(); }
                @Override public JSONObject parametersSchema() { return tool.parametersSchema(); }
                @Override public String execute(JSONObject args) throws IOException {
                    if (closed || !permitted(tool, livePermissionCheck)) return Json.obj("isError", true, "error", "MCP permission denied or configuration reloaded").toJSONString();
                    return tool.execute(args);
                }
            });
        }
        return List.copyOf(result);
    }
    private static boolean permitted(McpAgentTool t, Predicate<String> check) {
        return (t.permission().isBlank() || check.test(t.permission())) &&
                (t.readOnly() || !t.writePermission().isBlank() && check.test(t.writePermission()));
    }
    public List<String> statusLines() {
        if (entries.isEmpty()) return List.of("MCP: no enabled servers");
        return entries.stream().map(e -> e.client.config().id() + ": " + e.status).toList();
    }
    public List<String> toolLines(String server) {
        List<String> lines = new ArrayList<>();
        for (Entry e : entries) if (server.isBlank() || server.equals(e.client.config().id())) {
            for (McpAgentTool tool : e.tools) lines.add(tool.serverId() + "/" + Json.safeName(tool.remoteName()) +
                    " -> " + tool.name() + (tool.readOnly() ? " [read-only]" : " [write permission required]"));
        }
        return lines;
    }
    public String guidanceFor(Predicate<String> hasPermission) {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries) {
            McpServerConfig c = e.client.config();
            if (!c.permission().isBlank() && !hasPermission.test(c.permission())) continue;
            if (!c.guidance().isBlank()) out.append("\nConfigured MCP context (server ").append(c.id()).append("): ")
                    .append(c.guidance(), 0, Math.min(c.guidance().length(), 4000));
        }
        return out.toString();
    }
    public String diagnosticCall(String server, String remoteName, JSONObject args, Predicate<String> permission) throws IOException {
        if (closed) throw new IOException("MCP registry is closed");
        for (Entry e : entries) if (e.client.config().id().equals(server)) {
            for (McpAgentTool tool : e.tools) if (tool.remoteName().equals(remoteName)) {
                if (!permitted(tool, permission)) throw new IOException("MCP permission denied");
                if (!tool.readOnly()) throw new IOException("Diagnostic calls are read-only");
                return tool.execute(args);
            }
        }
        throw new IOException("Tool is unavailable or excluded by the allow/read-only policy");
    }
    @Override public void close() {
        closed = true;
        for (Entry e : entries) { e.tools = List.of(); e.client.close(); }
        workers.shutdownNow();
    }
}
