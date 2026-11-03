package io.github.mrgucci1.aiChatBuddy.mcp;

import org.json.simple.JSONObject;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Streamable HTTP: JSON and SSE responses, bounded reads, no redirects or credential logging. */
final class HttpMcpTransport implements McpTransport {
    private final McpServerConfig config;
    private final HttpClient http;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile String session;
    private volatile String lastVersion;
    private volatile boolean closed;
    HttpMcpTransport(McpServerConfig config) {
        this.config = config;
        http = HttpClient.newBuilder().connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private HttpRequest.Builder builder(String version, JSONObject message, Duration timeout, Map<String,String> extra) {
        HttpRequest.Builder b = HttpRequest.newBuilder(config.url()).timeout(timeout)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json").header("Accept-Encoding", "identity");
        config.headers().forEach(b::header);
        if (!config.token().isEmpty()) b.header("Authorization", "Bearer " + config.token());
        if (version != null) b.header("MCP-Protocol-Version", version);
        if (session != null && !"2026-07-28".equals(version)) b.header("Mcp-Session-Id", session);
        if ("2026-07-28".equals(version) && message != null && message.containsKey("method")) {
            b.header("Mcp-Method", message.get("method").toString());
            if (message.get("params") instanceof JSONObject p && p.get("name") instanceof String name)
                b.header("Mcp-Name", McpHeaders.encode(name));
        }
        extra.forEach(b::header);
        return b;
    }
    @Override public JSONObject exchange(JSONObject message, String version, Map<String,String> extra, Duration timeout) throws IOException {
        if (closed) throw new McpException("MCP connection is closed");
        lastVersion = version;
        long deadline = System.nanoTime() + timeout.toNanos();
        HttpResponse<InputStream> response;
        try {
            response = http.send(builder(version, message, timeout, extra)
                    .POST(HttpRequest.BodyPublishers.ofString(message.toJSONString(), StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new McpException("MCP HTTP request interrupted");
        } catch (IOException | IllegalArgumentException e) {
            throw new McpException("MCP HTTP connection failed (" + e.getClass().getSimpleName() + ")");
        }
        if ("initialize".equals(message.get("method")) && response.statusCode() / 100 == 2) {
            String value = response.headers().firstValue("Mcp-Session-Id").orElse(null);
            if (value != null && (value.length() > 1024 || !value.chars().allMatch(c -> c >= 33 && c <= 126))) {
                response.body().close(); throw new McpException("Invalid MCP session header");
            }
            session = value;
        }
        String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        try (InputStream body = response.body()) {
            Future<JSONObject> read = readers.submit(() -> {
                if (response.statusCode() / 100 != 2) {
                    byte[] bytes = body.readNBytes(Math.min(config.maxResponseBytes(), 65536));
                    JSONObject error = null;
                    try { error = Json.parse(new String(bytes, StandardCharsets.UTF_8)); } catch (IOException ignored) {}
                    int code = 0; JSONObject data = null;
                    if (error != null && error.get("error") instanceof JSONObject e) {
                        if (e.get("code") instanceof Number n) code = n.intValue();
                        if (e.get("data") instanceof JSONObject d) data = d;
                    }
                    throw new McpException("MCP HTTP " + response.statusCode() + (code == 0 ? "" : " (RPC " + code + ")"), response.statusCode(), code, data);
                }
                if (!message.containsKey("id") || !message.containsKey("method")) return Json.obj(); // notifications: normally 202
                if (type.startsWith("text/event-stream")) return readSse(body, message.get("id"), version);
                if (!type.startsWith("application/json")) throw new McpException("MCP response is not JSON/SSE");
                byte[] bytes = body.readNBytes(config.maxResponseBytes() + 1);
                if (bytes.length > config.maxResponseBytes()) throw new McpException("MCP response exceeded max-response-bytes");
                return Json.parse(new String(bytes, StandardCharsets.UTF_8));
            });
            try { return read.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (TimeoutException e) { read.cancel(true); throw new McpException("MCP HTTP response timed out"); }
            catch (InterruptedException e) { read.cancel(true); Thread.currentThread().interrupt(); throw new McpException("MCP response interrupted"); }
            catch (ExecutionException e) {
                if (e.getCause() instanceof IOException ioe) throw ioe;
                throw new McpException("MCP response decoding failed");
            }
        }
    }
    private JSONObject readSse(InputStream in, Object id, String version) throws IOException {
        InputStream bounded = new FilterInputStream(in) {
            int remaining = config.maxResponseBytes();
            @Override public int read() throws IOException {
                int n = super.read(); if (n != -1 && --remaining < 0) throw new McpException("MCP SSE response exceeded limit"); return n;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = in.read(b, off, Math.min(len, Math.max(1, remaining + 1)));
                if (n > 0 && (remaining -= n) < 0) throw new McpException("MCP SSE response exceeded limit"); return n;
            }
        };
        BufferedReader reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8));
        StringBuilder data = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                JSONObject result = event(data, id, version); data.setLength(0); if (result != null) return result;
            } else if (line.startsWith("data:")) {
                String field = line.substring(5); if (field.startsWith(" ")) field = field.substring(1);
                if (!data.isEmpty()) data.append('\n'); data.append(field);
            }
        }
        JSONObject result = event(data, id, version);
        if (result != null) return result;
        throw new McpException("MCP SSE ended before the RPC response; request was not replayed");
    }
    private JSONObject event(StringBuilder data, Object id, String version) throws IOException {
        if (data.isEmpty() || data.toString().isBlank()) return null;
        JSONObject object = Json.parse(data.toString());
        if (object.containsKey("method")) {
            if (object.containsKey("id") && !"2026-07-28".equals(version)) {
                JSONObject reply = "ping".equals(object.get("method"))
                        ? Json.obj("jsonrpc", "2.0", "id", object.get("id"), "result", Json.obj())
                        : Json.obj("jsonrpc", "2.0", "id", object.get("id"), "error", Json.obj("code", -32601, "message", "Client capability not supported"));
                // No sampling, roots or elicitation capability is advertised.
                exchange(reply, version, Map.of(), Duration.ofSeconds(3));
            }
            return null;
        }
        return Objects.equals(id, object.get("id")) ? object : null;
    }
    @Override public void notify(JSONObject message, String version) throws IOException {
        exchange(message, version, Map.of(), config.timeout());
    }
    @Override public void close() {
        if (closed) return; closed = true;
        if (session != null) {
            try { http.sendAsync(builder(lastVersion, null, Duration.ofSeconds(2), Map.of()).DELETE().build(), HttpResponse.BodyHandlers.discarding()); }
            catch (RuntimeException ignored) {}
        }
        readers.shutdownNow();
        http.shutdown();
    }
}
