package io.github.mrgucci1.aiChatBuddy.mcp;

import org.json.simple.JSONObject;
import org.json.simple.JSONArray;
import java.util.*;

/** Optional fallback for older Gemini endpoints. JSON Schema is the default and is kept intact. */
public final class SchemaAdapter {
    private static final Set<String> KEYS = Set.of("type", "description", "format", "nullable", "enum", "required",
            "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength", "pattern", "minProperties", "maxProperties");
    private SchemaAdapter() {}
    public static JSONObject toOpenApi(JSONObject schema) { return convert(schema, schema, new HashSet<>(), 0); }
    @SuppressWarnings("unchecked")
    private static JSONObject convert(JSONObject input, JSONObject root, Set<String> refs, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Schema exceeds supported nesting");
        if (input.get("$ref") instanceof String ref) {
            if (!ref.startsWith("#/") || !refs.add(ref)) throw new IllegalArgumentException("Recursive or remote schema reference requires json-schema mode");
            Object target = root;
            for (String key : ref.substring(2).split("/")) target = target instanceof Map<?,?> m ? m.get(key.replace("~1", "/").replace("~0", "~")) : null;
            if (!(target instanceof JSONObject object)) throw new IllegalArgumentException("Unresolved local schema reference");
            JSONObject result = convert(object, root, refs, depth + 1); refs.remove(ref); return result;
        }
        JSONObject out = new JSONObject();
        for (String key : KEYS) if (input.containsKey(key)) out.put(key, input.get(key));
        Object type = input.get("type");
        if (type instanceof List<?> types) {
            List<?> nonNull = types.stream().filter(t -> !"null".equals(t)).toList();
            if (nonNull.size() != 1) throw new IllegalArgumentException("Union schema requires json-schema mode");
            out.put("type", nonNull.get(0)); out.put("nullable", types.contains("null"));
        }
        Object composition = input.containsKey("anyOf") ? input.get("anyOf") : input.get("oneOf");
        if (composition instanceof List<?> branches) {
            List<JSONObject> nonNull = new ArrayList<>(); boolean nullable = false;
            for (Object raw : branches) {
                if (!(raw instanceof JSONObject b)) throw new IllegalArgumentException("Invalid schema branch");
                if ("null".equals(b.get("type"))) nullable = true; else nonNull.add(b);
            }
            if (nonNull.size() == 1) out.putAll(convert(nonNull.get(0), root, refs, depth + 1));
            else {
                JSONArray choices = new JSONArray();
                for (JSONObject branch : nonNull) choices.add(convert(branch, root, refs, depth + 1));
                out.put("anyOf", choices);
            }
            if (nullable) out.put("nullable", true);
        }
        if (input.containsKey("allOf")) throw new IllegalArgumentException("allOf schema requires json-schema mode");
        if (input.get("properties") instanceof JSONObject properties) {
            JSONObject converted = new JSONObject();
            properties.forEach((key, value) -> {
                if (!(value instanceof JSONObject object)) throw new IllegalArgumentException("Invalid property schema");
                converted.put(key, convert(object, root, new HashSet<>(refs), depth + 1));
            });
            out.put("properties", converted);
        }
        if (input.get("items") instanceof JSONObject items) out.put("items", convert(items, root, refs, depth + 1));
        if ("null".equals(out.get("type"))) { out.put("type", "string"); out.put("nullable", true); }
        return out;
    }
}
