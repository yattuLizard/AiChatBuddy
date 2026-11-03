package io.github.mrgucci1.aiChatBuddy.mcp;

import org.json.simple.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** MCP 2026-07-28 header mirroring, including safe Unicode value encoding. */
final class McpHeaders {
    record Binding(List<String> path, String header, String type) {}
    static String encode(String s) {
        boolean safe = s.equals(s.strip()) && s.chars().allMatch(c -> c >= 32 && c <= 126);
        if (safe && !(s.startsWith("=?base64?") && s.endsWith("?="))) return s;
        return "=?base64?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
    static List<Binding> bindings(JSONObject schema) throws IOException {
        List<Binding> out = new ArrayList<>(); Set<String> used = new HashSet<>();
        visit(schema, List.of(), true, out, used, 0);
        return List.copyOf(out);
    }
    private static void visit(Object raw, List<String> path, boolean reachable, List<Binding> out,
                              Set<String> used, int depth) throws IOException {
        if (depth > 64) throw new IOException("MCP schema nesting is too deep");
        if (raw instanceof Map<?,?> map) {
            if (map.containsKey("x-mcp-header")) {
                Object h = map.get("x-mcp-header"); String type = Objects.toString(map.get("type"), "");
                if (!reachable || path.isEmpty() || !(h instanceof String name) ||
                    !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") ||
                    !Set.of("string","boolean","integer").contains(type) ||
                    !used.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Invalid x-mcp-header annotation");
                out.add(new Binding(List.copyOf(path), "Mcp-Param-" + h, type));
            }
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()); Object value = entry.getValue();
                if (key.equals("properties") && value instanceof Map<?,?> props) {
                    for (var p : props.entrySet()) {
                        List<String> child = new ArrayList<>(path); child.add(p.getKey().toString());
                        visit(p.getValue(), child, reachable, out, used, depth + 1);
                    }
                } else if (!Set.of("default", "examples", "enum", "const").contains(key)) {
                    visit(value, path, false, out, used, depth + 1);
                }
            }
        } else if (raw instanceof List<?> list) {
            for (Object child : list) visit(child, path, false, out, used, depth + 1);
        }
    }
    static Map<String,String> extract(List<Binding> bindings, JSONObject args) throws IOException {
        Map<String,String> headers = new LinkedHashMap<>();
        for (Binding b : bindings) {
            Object value = args;
            for (String key : b.path()) value = value instanceof Map<?,?> m ? m.get(key) : null;
            if (value == null) continue;
            if (b.type().equals("string") && !(value instanceof String) ||
                b.type().equals("boolean") && !(value instanceof Boolean)) throw new IOException("Invalid header-bound tool argument");
            String encoded;
            if (b.type().equals("integer")) {
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) ||
                    n.doubleValue() != Math.rint(n.doubleValue()) || Math.abs(n.doubleValue()) > 9_007_199_254_740_991L)
                    throw new IOException("Header integer is outside the safe range");
                encoded = Long.toString(n.longValue());
            } else encoded = value.toString();
            if (encoded.length() > 8192) throw new IOException("Header-bound argument is too large");
            headers.put(b.header(), encode(encoded));
        }
        return headers;
    }
}
