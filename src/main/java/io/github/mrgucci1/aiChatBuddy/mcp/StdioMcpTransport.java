package io.github.mrgucci1.aiChatBuddy.mcp;

import org.json.simple.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One admin-configured process per server; never invokes a shell implicitly. */
final class StdioMcpTransport implements McpTransport {
    private final Process process;
    private final Writer writer;
    private final BlockingQueue<Object> incoming = new LinkedBlockingQueue<>(1024);
    private volatile boolean closed;
    StdioMcpTransport(McpServerConfig config) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(config.command());
        if (config.directory() != null) builder.directory(config.directory().toFile());
        // Prevent unrelated host credentials being inherited by a child server.
        Map<String,String> original = new HashMap<>(builder.environment());
        builder.environment().clear();
        for (String key : List.of("PATH", "HOME", "USERPROFILE", "SYSTEMROOT", "WINDIR", "TMP", "TEMP", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME"))
            if (original.containsKey(key)) builder.environment().put(key, original.get(key));
        builder.environment().putAll(config.environment());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try { process = builder.start(); }
        catch (IOException e) { throw new McpException("Could not start configured stdio executable"); }
        writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread.ofVirtual().name("aichat-mcp-stdio-" + config.id()).start(() -> {
            try (InputStream in = process.getInputStream()) {
                ByteArrayOutputStream line = new ByteArrayOutputStream(); int value;
                while ((value = in.read()) != -1) {
                    if (value == '\n') {
                        if (line.size() > 0) {
                            JSONObject message = Json.parse(line.toString(StandardCharsets.UTF_8));
                            if (!incoming.offer(message)) throw new McpException("Too many stdio messages");
                            line.reset();
                        }
                    } else {
                        if (line.size() >= config.maxResponseBytes()) throw new McpException("stdio message exceeded limit");
                        line.write(value);
                    }
                }
                incoming.offer(new McpException("MCP stdio process ended"));
            } catch (IOException e) { incoming.offer(e); }
        });
    }
    private synchronized void send(JSONObject message) throws IOException {
        if (closed || !process.isAlive()) throw new McpException("MCP stdio process is not running");
        writer.write(message.toJSONString()); writer.write('\n'); writer.flush();
    }
    @Override public JSONObject exchange(JSONObject message, String version, Map<String,String> ignored, Duration timeout) throws IOException {
        send(message);
        if (!message.containsKey("id")) return Json.obj();
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                Object raw = incoming.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (raw == null) throw new McpException("MCP stdio response timed out");
                if (raw instanceof IOException e) throw e;
                JSONObject object = (JSONObject) raw;
                if (object.containsKey("method")) {
                    if (object.containsKey("id")) send("ping".equals(object.get("method"))
                            ? Json.obj("jsonrpc", "2.0", "id", object.get("id"), "result", Json.obj())
                            : Json.obj("jsonrpc", "2.0", "id", object.get("id"), "error", Json.obj("code", -32601, "message", "Client capability not supported")));
                    continue;
                }
                if (Objects.equals(message.get("id"), object.get("id"))) return object;
                if (System.nanoTime() >= deadline) throw new McpException("MCP stdio response timed out");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new McpException("MCP stdio request interrupted"); }
    }
    @Override public void notify(JSONObject message, String version) throws IOException { send(message); }
    @Override public void close() {
        if (closed) return; closed = true;
        try { writer.close(); } catch (IOException ignored) {}
        process.destroy();
        Thread.ofVirtual().start(() -> {
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
        });
        incoming.offer(new McpException("MCP connection closed"));
    }
}
