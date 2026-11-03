package io.github.mrgucci1.aiChatBuddy.providers;

import io.github.mrgucci1.aiChatBuddy.*;
import io.github.mrgucci1.aiChatBuddy.mcp.Json;
import io.github.mrgucci1.aiChatBuddy.tools.AgentTool;
import org.json.simple.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class GeminiProviderTest {
    private final GeminiProvider provider = new GeminiProvider("not-a-real-key", "http://localhost/not-used", .5, .9, Logger.getAnonymousLogger());
    @Test void personaIsPreservedInOneSystemInstruction() {
        JSONObject request = provider.buildRequest(List.of(ChatMessage.system("あなたはセラフィナです．"),ChatMessage.system("Use live tools."),ChatMessage.user("hi")),List.of());
        String text = ((JSONObject)((JSONArray)((JSONObject) request.get("system_instruction")).get("parts")).getFirst()).get("text").toString();
        assertEquals("あなたはセラフィナです．\n\nUse live tools.",text);
    }
    @Test void arbitraryMcpJsonSchemaIsPreserved() {
        JSONObject schema = Json.obj("type","object","properties",Json.obj("dimension",Json.obj("anyOf",Json.arr(Json.obj("type","string"),Json.obj("type","null")),"default",null)));
        AgentTool tool = new AgentTool() {
            public String name(){return "mcp_bluemap_bm_vehicles";} public String description(){return "live data";}
            public JSONObject parametersSchema(){return schema;} public String execute(JSONObject a){return "unused";}
        };
        JSONObject request = provider.buildRequest(List.of(ChatMessage.user("台数は？")),List.of(tool));
        JSONObject t=(JSONObject)((JSONArray)request.get("tools")).getFirst();
        JSONObject declaration=(JSONObject)((JSONArray)t.get("functionDeclarations")).getFirst();
        assertEquals(schema,declaration.get("parametersJsonSchema")); assertFalse(declaration.containsKey("parameters"));
    }
    @Test void nativeThoughtSignaturesAndFunctionIdsRoundTrip() throws Exception {
        JSONObject content=Json.obj("role","model","parts",Json.arr(Json.obj("thought",true,"text","hidden","thoughtSignature","sig-text"),
                Json.obj("functionCall",Json.obj("name","mcp_bluemap_bm_vehicles","args",Json.obj("limit",1),"id","native-123"),"thoughtSignature","sig-call")));
        ProviderResponse response=provider.parseResponse(Json.obj("candidates",Json.arr(Json.obj("content",content))).toJSONString());
        assertFalse(response.isText()); assertEquals("native-123",response.toolCalls().getFirst().getId());
        JSONObject request=provider.buildRequest(List.of(ChatMessage.user("count"),ChatMessage.assistantToolCalls(response.toolCalls(),response.nativeContent()),
                ChatMessage.tool("native-123","mcp_bluemap_bm_vehicles","{\"total\":33}")),List.of());
        JSONArray contents=(JSONArray)request.get("contents"); assertEquals(Json.parse(content.toJSONString()),contents.get(1));
        JSONObject reply=(JSONObject)((JSONObject)((JSONArray)((JSONObject)contents.get(2)).get("parts")).getFirst()).get("functionResponse");
        assertEquals("native-123",reply.get("id")); assertEquals(33L,((JSONObject)reply.get("response")).get("total"));
    }
    @Test void generatedLocalIdNotSentAsGeminiFunctionResponseId() throws Exception {
        JSONObject content=Json.obj("role","model","parts",Json.arr(Json.obj("functionCall",Json.obj("name","echo","args",Json.obj()))));
        var response=provider.parseResponse(Json.obj("candidates",Json.arr(Json.obj("content",content))).toJSONString());
        String id=response.toolCalls().getFirst().getId(); assertTrue(id.startsWith("gemini-local-"));
        var request=provider.buildRequest(List.of(ChatMessage.assistantToolCalls(response.toolCalls(),response.nativeContent()),ChatMessage.tool(id,"echo","ok")),List.of());
        JSONObject turn=(JSONObject)((JSONArray)request.get("contents")).get(1);
        JSONObject function=(JSONObject)((JSONObject)((JSONArray)turn.get("parts")).getFirst()).get("functionResponse");
        assertFalse(function.containsKey("id"));
    }
    @Test void partialThinkingOutputNotExposed() throws Exception {
        var r=provider.parseResponse(Json.obj("candidates",Json.arr(Json.obj("content",Json.obj("parts",Json.arr(Json.obj("text","<thought>secret</thought>回答","thought",true)))))).toJSONString());
        assertEquals("回答",r.text());
    }
    @Test void invalidUpstreamResponseFailsWithoutDumpingBody() {
        var e=assertThrows(java.io.IOException.class,()->provider.parseResponse("{\"private_api_key\":\"dont_log\"}"));
        assertFalse(e.getMessage().contains("dont_log"));
    }
}
