package io.github.mrgucci1.aiChatBuddy.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

/** Immutable, admin-supplied connection settings; independent of Bukkit for testability. */
public record McpServerConfig(
        String id, String transport, URI url, List<String> command, Path directory,
        Map<String,String> environment, Map<String,String> headers, String token,
        String protocolVersion, Duration timeout, int maxResponseBytes, int maxResultChars,
        int maxTools, boolean readOnly, boolean trustReadOnlyHints,
        Set<String> allowTools, Set<String> denyTools, Set<String> readOnlyTools,
        String permission, String writePermission, String guidance) {

    public static final List<String> VERSIONS = List.of("2026-07-28", "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Set<String> RESERVED_HEADERS = Set.of("host", "content-length", "accept", "content-type", "connection", "upgrade", "mcp-protocol-version", "mcp-session-id", "mcp-method", "mcp-name");
    public McpServerConfig {
        command = List.copyOf(command); environment = Map.copyOf(environment); headers = Map.copyOf(headers);
        allowTools = Set.copyOf(allowTools); denyTools = Set.copyOf(denyTools); readOnlyTools = Set.copyOf(readOnlyTools);
    }
    public static McpServerConfig from(String id, Map<String, Object> values) {
        return from(id, values, System::getenv);
    }
    public static McpServerConfig from(String id, Map<String, Object> v, Function<String,String> env) {
        if (!id.matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException("Server ID must be 1-32 ASCII letters, digits, _ or -");
        String transport = str(v, "transport", "streamable-http").toLowerCase(Locale.ROOT);
        if (!Set.of("streamable-http", "stdio").contains(transport)) throw new IllegalArgumentException("Supported transports: streamable-http, stdio");
        URI url = null;
        List<String> command = new ArrayList<>();
        Path directory = null;
        if (transport.equals("streamable-http")) {
            try { url = URI.create(expand(str(v, "url", ""), env)); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid MCP endpoint URL"); }
            if (url.getHost() == null || url.getUserInfo() != null || url.getFragment() != null ||
                !("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme())))
                throw new IllegalArgumentException("MCP endpoint must be http(s), without userinfo or fragment");
            boolean loopback = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(url.getHost());
            if (url.getScheme().equalsIgnoreCase("http") && !loopback && !bool(v, "allow-insecure-http", false))
                throw new IllegalArgumentException("Plain HTTP requires allow-insecure-http: true for a trusted private network");
        } else {
            String executable = expand(str(v, "command", ""), env);
            if (executable.isBlank()) throw new IllegalArgumentException("stdio requires command");
            command.add(executable);
            for (String arg : strings(v.get("args"))) command.add(expand(arg, env));
            String dir = expand(str(v, "working-directory", ""), env);
            if (!dir.isBlank()) directory = Path.of(dir).toAbsolutePath();
        }
        String token = expand(str(v, "token", ""), env);
        String tokenEnv = str(v, "token-env", "");
        if (!tokenEnv.isBlank()) {
            token = env.apply(tokenEnv);
            if (token == null || token.isBlank()) throw new IllegalArgumentException("Configured token-env is unset");
        }
        rejectControl(token);
        Map<String,String> headers = stringMap(v.get("headers"), env);
        for (var e : headers.entrySet()) {
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            if (!e.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || RESERVED_HEADERS.contains(lower) || lower.startsWith("mcp-param-"))
                throw new IllegalArgumentException("Reserved or invalid MCP HTTP header");
            rejectControl(e.getValue());
            if (lower.equals("authorization") && !token.isBlank()) throw new IllegalArgumentException("Use either token or Authorization header, not both");
        }
        String version = str(v, "protocol-version", "auto");
        if (!version.equals("auto") && !VERSIONS.contains(version)) throw new IllegalArgumentException("Unsupported MCP protocol-version");
        if (transport.equals("streamable-http") && version.equals("2024-11-05"))
            throw new IllegalArgumentException("Legacy HTTP+SSE is not supported; use Streamable HTTP");
        return new McpServerConfig(id, transport, url, command, directory, stringMap(v.get("env"), env), headers, token,
                version, Duration.ofSeconds(number(v, "timeout-seconds", 20, 1, 120)),
                number(v, "max-response-bytes", 2_097_152, 1024, 16_777_216),
                number(v, "max-result-chars", 12000, 512, 100000), number(v, "max-tools", 64, 1, 256),
                bool(v, "read-only", true), bool(v, "trust-read-only-hints", false),
                new HashSet<>(strings(v.get("allow-tools"))), new HashSet<>(strings(v.get("deny-tools"))),
                new HashSet<>(strings(v.get("read-only-tools"))), str(v, "permission", "aichatbuddy.mcp.use"),
                str(v, "write-permission", "aichatbuddy.mcp.write"), str(v, "guidance", ""));
    }
    private static void rejectControl(String s) {
        if (s.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("Control character in credential/header");
    }
    public static String expand(String value, Function<String,String> env) {
        Matcher m = ENV.matcher(value); StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = env.apply(m.group(1));
            if (replacement == null || replacement.isEmpty()) throw new IllegalArgumentException("Referenced environment variable is unset");
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        return m.appendTail(out).toString();
    }
    private static Map<String,String> stringMap(Object raw, Function<String,String> env) {
        Map<String,String> result = new LinkedHashMap<>();
        if (raw instanceof Map<?,?> map) map.forEach((k,val) -> result.put(String.valueOf(k), expand(String.valueOf(val), env)));
        return result;
    }
    public static List<String> strings(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }
    public static boolean bool(Map<String,Object> map, String key, boolean fallback) {
        Object v = map.get(key); return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }
    public static String str(Map<String,Object> map, String key, String fallback) { return Objects.toString(map.get(key), fallback); }
    public static int number(Map<String,Object> map, String key, int fallback, int min, int max) {
        int n = map.containsKey(key) ? Integer.parseInt(map.get(key).toString()) : fallback;
        if (n < min || n > max) throw new IllegalArgumentException("Configuration value out of range: " + key);
        return n;
    }
    public static boolean matches(Set<String> patterns, String name) {
        for (String pattern : patterns) {
            String regex = "\\Q" + pattern.replace("*", "\\E.*\\Q") + "\\E";
            if (name.matches(regex)) return true;
        }
        return false;
    }
    @Override public String toString() { return "McpServerConfig[id=" + id + ",transport=" + transport + "]"; }
}
