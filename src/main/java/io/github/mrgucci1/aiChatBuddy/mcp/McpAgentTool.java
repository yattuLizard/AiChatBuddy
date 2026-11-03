package io.github.mrgucci1.aiChatBuddy.mcp;

import io.github.mrgucci1.aiChatBuddy.tools.AgentTool;
import org.json.simple.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.logging.Logger;

/** An MCP tool adapted to the existing AiChatBuddy function-calling interface. */
public final class McpAgentTool implements AgentTool {
    private final McpClient client;
    private final String remoteName;
    private final String name;
    private final String description;
    private final JSONObject schema;
    private final boolean readOnly;
    private final Logger logger;
    public McpAgentTool(McpClient client, JSONObject tool, Logger logger) {
        this.client = client; this.logger = logger;
        remoteName = (String) tool.get("name"); name = alias(client.config().id(), remoteName);
        String desc = Json.string(tool.get("description"), remoteName);
        description = "MCP server " + client.config().id() + ", tool " + remoteName + ". " +
                desc.substring(0, Math.min(desc.length(), 1800));
        schema = Json.copy((JSONObject) tool.get("inputSchema"));
        readOnly = isReadOnly(client.config(), tool);
    }
    public static boolean isReadOnly(McpServerConfig config, JSONObject tool) {
        String name = Json.string(tool.get("name"), "");
        if (McpServerConfig.matches(config.readOnlyTools(), name)) return true; // administrator attestation
        return config.trustReadOnlyHints() && tool.get("annotations") instanceof JSONObject a && Boolean.TRUE.equals(a.get("readOnlyHint"));
    }
    public static boolean allowed(McpServerConfig config, JSONObject tool) {
        String name = Json.string(tool.get("name"), "");
        if (McpServerConfig.matches(config.denyTools(), name)) return false;
        if (!config.allowTools().isEmpty() && !McpServerConfig.matches(config.allowTools(), name)) return false;
        if (isReadOnly(config, tool)) return true;
        // Unknown/mutating tools require explicit allow-list membership AND disabling read-only.
        return !config.readOnly() && McpServerConfig.matches(config.allowTools(), name);
    }
    public static String alias(String server, String tool) {
        String raw = "mcp_" + server + "_" + tool;
        String safe = raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (safe.equals(raw) && safe.length() <= 64) return safe;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
            return safe.substring(0, Math.min(safe.length(), 51)) + "_" + hash;
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    @Override public String name() { return name; }
    @Override public String description() { return description; }
    @Override public JSONObject parametersSchema() { return Json.copy(schema); }
    public String remoteName() { return remoteName; }
    public String serverId() { return client.config().id(); }
    public boolean readOnly() { return readOnly; }
    public String permission() { return client.config().permission(); }
    public String writePermission() { return client.config().writePermission(); }
    @Override public String execute(JSONObject args) throws IOException {
        long start = System.nanoTime();
        try {
            JSONObject result = client.callTool(remoteName, args);
            boolean error = Boolean.TRUE.equals(result.get("isError"));
            logger.info("[MCP] server=" + serverId() + " tool=" + Json.safeName(remoteName) + " result=" + (error ? "error" : "ok") + " ms=" + (System.nanoTime() - start) / 1_000_000);
            // Do not pass images/audio/base64 into a text-only chat model.
            JSONObject output = Json.obj("server", serverId(), "tool", remoteName, "isError", error);
            if (result.containsKey("structuredContent")) output.put("structuredContent", result.get("structuredContent"));
            List<Object> content = new ArrayList<>();
            if (result.get("content") instanceof List<?> parts) for (Object raw : parts) {
                if (!(raw instanceof JSONObject part)) continue;
                String type = Json.string(part.get("type"), "unknown");
                if (type.equals("text")) {
                    boolean duplicate = false;
                    if (!error && result.get("structuredContent") instanceof JSONObject structured && part.get("text") instanceof String text) {
                        try { duplicate = structured.equals(Json.parse(text)); } catch (IOException ignored) {}
                    }
                    if (!duplicate) content.add(Json.obj("type", "text", "text", part.get("text")));
                } else if (type.equals("resource_link")) {
                    content.add(Json.obj("type", type, "name", part.get("name"), "uri", part.get("uri")));
                } else if (type.equals("resource") && part.get("resource") instanceof JSONObject r) {
                    content.add(Json.obj("type", type, "uri", r.get("uri"), "text", r.get("text")));
                } else content.add(Json.obj("type", type, "omitted", "Non-text payload not forwarded"));
            }
            if (!content.isEmpty()) output.put("content", content);
            String text = output.toJSONString();
            int max = client.config().maxResultChars();
            if (text.length() > max) return Json.obj("server", serverId(), "tool", remoteName, "isError", error,
                    "truncated", true, "notice", "Result is incomplete. Narrow the query or use pagination; do not infer missing facts.",
                    "partialContent", text.substring(0, max)).toJSONString();
            return text;
        } catch (IOException e) {
            logger.warning("[MCP] server=" + serverId() + " tool=" + Json.safeName(remoteName) + " failed=" + e.getClass().getSimpleName());
            return Json.obj("server", serverId(), "tool", remoteName, "isError", true,
                    "error", e instanceof McpException ? e.getMessage() : "MCP connection or decoding failed",
                    "notice", "Live data is unavailable. Do not invent values. A failed call was not automatically replayed.").toJSONString();
        }
    }
}
