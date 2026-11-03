package io.github.mrgucci1.aiChatBuddy.mcp;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.JSONParser;
import java.io.IOException;
import java.util.*;

/** JSON helpers. No request or credential contents are included in parse errors. */
public final class Json {
    private Json() {}
    @SuppressWarnings("unchecked")
    public static JSONObject obj(Object... pairs) {
        JSONObject o = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) o.put(pairs[i], pairs[i + 1]);
        return o;
    }
    @SuppressWarnings("unchecked")
    public static JSONArray arr(Object... values) {
        JSONArray a = new JSONArray(); Collections.addAll(a, values); return a;
    }
    public static JSONObject object(Object value) throws IOException {
        if (!(value instanceof JSONObject o)) throw new IOException("Expected a JSON object");
        return o;
    }
    public static JSONObject parse(String text) throws IOException {
        try { return object(new JSONParser().parse(text)); }
        catch (org.json.simple.parser.ParseException e) { throw new IOException("Invalid JSON response"); }
    }
    public static JSONObject copy(JSONObject o) {
        try { return parse(o.toJSONString()); } catch (IOException e) { throw new IllegalArgumentException("Invalid JSON object"); }
    }
    public static String stringify(Object o) { return JSONValue.toJSONString(o); }
    public static String string(Object value, String fallback) { return value instanceof String s ? s : fallback; }
    public static String safeName(String s) { return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9_.:/-]", "_"); }
}
