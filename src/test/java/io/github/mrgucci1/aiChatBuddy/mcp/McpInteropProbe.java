package io.github.mrgucci1.aiChatBuddy.mcp;

import java.util.*;
import java.util.logging.Logger;
import org.json.simple.JSONObject;

/** CI entry point exercised against unmodified official Python SDK servers. */
public final class McpInteropProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("transport endpoint protocol [script]");
        Map<String,Object> values = new HashMap<>();
        values.put("transport", args[0]);
        values.put("protocol-version", args[2]);
        values.put("timeout-seconds", 10);
        values.put("read-only-tools", List.of("echo", "fail"));
        if (args[0].equals("stdio")) {
            values.put("command", args[1]);
            values.put("args", List.of("-u", args[3], "stdio"));
        } else values.put("url", args[1]);
        McpServerConfig settings = McpServerConfig.from("official-sdk", values);
        try (McpClient client = new McpClient(settings)) {
            List<JSONObject> tools = client.listTools();
            JSONObject echo = tools.stream().filter(t -> "echo".equals(t.get("name"))).findFirst().orElseThrow();
            McpAgentTool adapter = new McpAgentTool(client, echo, Logger.getAnonymousLogger());
            String result = adapter.execute(Json.obj("value", "日本語・Unicode 🚂", "count", 7));
            if (!result.contains("日本語・Unicode 🚂") || !result.contains("7")) throw new AssertionError("echo result mismatch: " + result);
            JSONObject failed = client.callTool("fail", Json.obj());
            if (!Boolean.TRUE.equals(failed.get("isError"))) throw new AssertionError("tool error flag lost");
            // A tool error must not poison the session/next request.
            JSONObject again = client.callTool("echo", Json.obj("value", "after-error", "count", 1));
            if (Boolean.TRUE.equals(again.get("isError"))) throw new AssertionError("tool error aborted session");
            if (!args[2].equals("auto") && !args[2].equals(client.version())) throw new AssertionError("wrong negotiated version");
            System.out.println("PASS official SDK: " + args[0] + " requested=" + args[2] + " negotiated=" + client.version() + " tools=" + tools.size());
        }
    }
}
