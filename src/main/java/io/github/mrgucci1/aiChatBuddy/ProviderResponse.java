package io.github.mrgucci1.aiChatBuddy;

import java.util.List;
import org.json.simple.JSONObject;

public class ProviderResponse {
    private final String text;
    private final List<ToolCall> toolCalls;
    private JSONObject nativeContent;

    private ProviderResponse(String text, List<ToolCall> toolCalls) {
        this.text = text;
        this.toolCalls = toolCalls;
    }

    public static ProviderResponse text(String text) {
        return new ProviderResponse(text, null);
    }

    public static ProviderResponse toolCalls(List<ToolCall> toolCalls) {
        return new ProviderResponse(null, toolCalls);
    }

    public static ProviderResponse toolCalls(List<ToolCall> toolCalls, JSONObject nativeContent) {
        ProviderResponse response = toolCalls(toolCalls);
        response.nativeContent = nativeContent;
        return response;
    }

    public JSONObject nativeContent() { return nativeContent; }

    public boolean isText() { return text != null; }
    public String text() { return text; }
    public List<ToolCall> toolCalls() { return toolCalls; }
}
