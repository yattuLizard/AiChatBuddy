package io.github.mrgucci1.aiChatBuddy.agent;

import io.github.mrgucci1.aiChatBuddy.*;
import io.github.mrgucci1.aiChatBuddy.conversation.ConversationManager;
import io.github.mrgucci1.aiChatBuddy.providers.*;
import io.github.mrgucci1.aiChatBuddy.mcp.*;
import io.github.mrgucci1.aiChatBuddy.tools.AgentTool;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
class AgentLoopTest {
    @Test void realMcpToolResultReturnsToModelAndNotPersistentHistory() throws Exception {
        try(var f=new TestMcpServer();var client=new McpClient(f.config(Map.of()))) {
            AgentTool tool=new McpAgentTool(client,client.listTools().getFirst(),Logger.getAnonymousLogger());
            AtomicInteger steps=new AtomicInteger();
            AIProvider provider=(history,tools)-> {
                assertEquals("Seraphina persona",history.getFirst().getContent());
                if(steps.getAndIncrement()==0) return ProviderResponse.toolCalls(List.of(new ToolCall("1",tool.name(),"{\"limit\":1}")));
                assertEquals(ChatMessage.Role.tool,history.getLast().getRole());
                assertTrue(history.getLast().getContent().contains("33"));
                return ProviderResponse.text("<thought>private</thought>33台だよ．");
            };
            var memory=new ConversationManager(8); UUID player=UUID.randomUUID();
            var loop=new AgentLoop(provider,memory,List.of(),5,Logger.getAnonymousLogger(),null);
            assertEquals("33台だよ．",loop.ask(player,"count","Seraphina persona",List.of(tool),null));
            assertEquals(2,memory.get(player).size());
            assertTrue(memory.get(player).stream().noneMatch(m->m.getRole()==ChatMessage.Role.tool||m.hasToolCalls()));
            assertEquals(1,f.calls.get());
        }
    }
    @Test void failedToolDoesNotAbortConversation() {
        AgentTool bad=new AgentTool(){public String name(){return "broken";}public String description(){return "test";}
            public org.json.simple.JSONObject parametersSchema(){return Json.obj("type","object");}
            public String execute(org.json.simple.JSONObject a) throws java.io.IOException{throw new java.io.IOException("SECRET");}};
        AtomicInteger steps=new AtomicInteger();
        AIProvider provider=(h,t)->steps.getAndIncrement()==0?ProviderResponse.toolCalls(List.of(new ToolCall("x","broken","{}"))):ProviderResponse.text("今は取得できないよ．");
        var loop=new AgentLoop(provider,new ConversationManager(8),List.of(bad),4,Logger.getAnonymousLogger(),null);
        assertEquals("今は取得できないよ．",loop.ask(UUID.randomUUID(),"test","persona"));
    }
    @Test void plainChatDoesNotInvokeMcp() {
        AtomicInteger executions=new AtomicInteger();
        AgentTool tool=new AgentTool(){public String name(){return "mcp_server_echo";}public String description(){return "test";}
            public org.json.simple.JSONObject parametersSchema(){return Json.obj("type","object");}
            public String execute(org.json.simple.JSONObject a){executions.incrementAndGet();return "data";}};
        var loop=new AgentLoop((h,t)->ProviderResponse.text("わたしはセラフィナ．"),new ConversationManager(8),List.of(tool),4,Logger.getAnonymousLogger(),null);
        assertEquals("わたしはセラフィナ．",loop.ask(UUID.randomUUID(),"自己紹介","persona"));assertEquals(0,executions.get());
    }
}
