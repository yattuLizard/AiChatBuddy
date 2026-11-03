package io.github.mrgucci1.aiChatBuddy.mcp;

import com.sun.net.httpserver.*;
import org.json.simple.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real local HTTP fixture, not a replacement for the client transport. */
public final class TestMcpServer implements AutoCloseable {
    public final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    public final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    public final List<Map<String,List<String>>> headers = new CopyOnWriteArrayList<>();
    public final AtomicInteger calls = new AtomicInteger();
    public final List<Throwable> failures = new CopyOnWriteArrayList<>();
    public String mode = "modern";
    public String expectedToken = "secret";
    public boolean pagination;
    public boolean cycle;
    public boolean toolError;
    public int holdMillis;
    public JSONObject schema = Json.obj("type", "object", "properties", Json.obj("loaded_only", Json.obj("type", "boolean"), "limit", Json.obj("type", "integer")));
    public TestMcpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor); server.createContext("/mcp", this::handle); server.start();
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"; }
    public McpServerConfig config(Map<String,Object> extra) {
        Map<String,Object> v = new LinkedHashMap<>(); v.put("url", url()); v.put("token", "secret");
        v.put("read-only-tools", List.of("bm_vehicles", "other")); v.putAll(extra);
        return McpServerConfig.from("test", v);
    }
    public static JSONObject tool(String name, JSONObject schema) {
        return Json.obj("name", name, "description", "Read current Sable vehicle data", "inputSchema", schema);
    }
    private void handle(HttpExchange ex) throws IOException {
        try {
            if (ex.getRequestMethod().equals("DELETE")) { ex.sendResponseHeaders(204, -1); ex.close(); return; }
            JSONObject req = Json.parse(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(req); headers.add(Map.copyOf(ex.getRequestHeaders()));
            if (!Objects.equals("Bearer " + expectedToken, ex.getRequestHeaders().getFirst("Authorization"))) {
                respond(ex, 401, Json.obj("secret", "DO_NOT_LEAK_TOKEN"), false); return;
            }
            String method = (String) req.get("method");
            if (!req.containsKey("id")) { ex.sendResponseHeaders(202, -1); ex.close(); return; }
            if ("legacy".equals(mode) && "server/discover".equals(method)) {
                respond(ex, 400, Json.obj("jsonrpc", "2.0", "id", req.get("id"), "error", Json.obj("code", -32600, "message", "initialize first")), false); return;
            }
            if ("malformed".equals(mode)) { respond(ex, 200, Json.obj("wrong", true), false); return; }
            if ("oversized".equals(mode)) { respond(ex, 200, Json.obj("data", "x".repeat(150000)), false); return; }
            JSONObject params = req.get("params") instanceof JSONObject p ? p : Json.obj();
            JSONObject result;
            switch (method) {
                case "server/discover" -> result = Json.obj("resultType", "complete", "supportedVersions", Json.arr("2026-07-28"), "capabilities", Json.obj("tools", Json.obj()));
                case "initialize" -> {
                    ex.getResponseHeaders().set("Mcp-Session-Id", "test-session");
                    result = Json.obj("protocolVersion", "2025-06-18", "capabilities", Json.obj("tools", Json.obj()), "serverInfo", Json.obj("name", "fixture", "version", "1"));
                }
                case "tools/list" -> {
                    if (pagination && !params.containsKey("cursor")) result = Json.obj("tools", Json.arr(tool("bm_vehicles", schema)), "nextCursor", "page-two");
                    else if (cycle) result = Json.obj("tools", Json.arr(), "nextCursor", "page-two");
                    else result = Json.obj("tools", Json.arr(tool(params.containsKey("cursor") ? "other" : "bm_vehicles", schema)));
                }
                case "tools/call" -> {
                    calls.incrementAndGet();
                    if (mode.equals("expired")) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                    JSONObject args = (JSONObject) params.get("arguments");
                    result = Json.obj("isError", toolError, "structuredContent", Json.obj("total", Boolean.TRUE.equals(args.get("loaded_only")) ? 2 : 33, "items", Json.arr()),
                            "content", Json.arr(Json.obj("type", "text", "text", "vehicle data")));
                }
                default -> throw new IllegalArgumentException("Unexpected method " + method);
            }
            if (!mode.equals("legacy") && !method.equals("initialize")) {
                org.junit.jupiter.api.Assertions.assertEquals("2026-07-28", ex.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                org.junit.jupiter.api.Assertions.assertEquals(method, ex.getRequestHeaders().getFirst("Mcp-Method"));
                org.junit.jupiter.api.Assertions.assertEquals("2026-07-28", ((JSONObject) params.get("_meta")).get("io.modelcontextprotocol/protocolVersion"));
            } else if (mode.equals("legacy") && !method.equals("initialize")) {
                org.junit.jupiter.api.Assertions.assertEquals("test-session", ex.getRequestHeaders().getFirst("Mcp-Session-Id"));
            }
            respond(ex, 200, Json.obj("jsonrpc", "2.0", "id", req.get("id"), "result", result), mode.equals("sse"));
        } catch (Throwable failure) {
            failures.add(failure);
            try { ex.sendResponseHeaders(500, -1); } catch (IOException ignored) {} ex.close();
        }
    }
    private void respond(HttpExchange ex, int status, JSONObject object, boolean sse) throws IOException {
        String body = object.toJSONString();
        if (sse) body = ": keepalive\r\n\r\nevent: message\r\ndata: " + Json.obj("jsonrpc","2.0","method","notifications/progress","params",Json.obj()).toJSONString()
                + "\r\n\r\nevent: message\r\ndata: " + body + "\r\n\r\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", sse ? "text/event-stream" : "application/json");
        ex.sendResponseHeaders(status, holdMillis > 0 ? 0 : bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            if (mode.equals("hang")) { try { Thread.sleep(holdMillis); } catch (InterruptedException ignored) {} }
            out.write(bytes); out.flush();
            if (sse && holdMillis > 0) { try { Thread.sleep(holdMillis); } catch (InterruptedException ignored) {} }
        }
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
