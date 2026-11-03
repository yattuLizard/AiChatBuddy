package io.github.mrgucci1.aiChatBuddy.mcp;

import java.io.IOException;
import org.json.simple.JSONObject;

/** Sanitized transport/protocol error. Raw peer messages may contain secrets and are never logged. */
public final class McpException extends IOException {
    private final int httpStatus;
    private final int rpcCode;
    private final JSONObject data;
    public McpException(String message) { this(message, 0, 0, null); }
    public McpException(String message, int httpStatus, int rpcCode, JSONObject data) {
        super(message); this.httpStatus = httpStatus; this.rpcCode = rpcCode; this.data = data;
    }
    public int httpStatus() { return httpStatus; }
    public int rpcCode() { return rpcCode; }
    public JSONObject data() { return data; }
    public boolean modernError() { return rpcCode == -32020 || rpcCode == -32021 || rpcCode == -32022; }
}
