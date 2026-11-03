package io.github.mrgucci1.aiChatBuddy.mcp;
import org.json.simple.JSONObject;
import java.io.*;
public final class StdioFixture {
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            JSONObject r = Json.parse(line); String method = (String) r.get("method");
            if (!r.containsKey("id")) continue;
            JSONObject result;
            if (method.equals("server/discover") && args[0].equals("legacy")) {
                System.out.println(Json.obj("jsonrpc", "2.0", "id", r.get("id"), "error", Json.obj("code", -32601, "message", "legacy"))); continue;
            }
            result = switch (method) {
                case "server/discover" -> Json.obj("supportedVersions", Json.arr("2026-07-28"), "capabilities", Json.obj("tools", Json.obj()));
                case "initialize" -> Json.obj("protocolVersion", "2025-11-25", "capabilities", Json.obj("tools", Json.obj()));
                case "tools/list" -> Json.obj("tools", Json.arr(Json.obj("name", "echo", "inputSchema", Json.obj("type", "object"))));
                case "tools/call" -> Json.obj("isError", false, "content", Json.arr(Json.obj("type", "text", "text", "ok")));
                default -> Json.obj();
            };
            System.out.println(Json.obj("jsonrpc", "2.0", "id", r.get("id"), "result", result));
        }
    }
}
