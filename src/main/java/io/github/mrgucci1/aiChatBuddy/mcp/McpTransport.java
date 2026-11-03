package io.github.mrgucci1.aiChatBuddy.mcp;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.json.simple.JSONObject;

interface McpTransport extends AutoCloseable {
    JSONObject exchange(JSONObject message, String version, Map<String,String> additionalHeaders, Duration timeout) throws IOException;
    void notify(JSONObject message, String version) throws IOException;
    @Override void close();
}
