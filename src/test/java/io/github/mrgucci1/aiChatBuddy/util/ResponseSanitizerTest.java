package io.github.mrgucci1.aiChatBuddy.util;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ResponseSanitizerTest {
    @Test void stripsCompleteReasoning() { assertEquals("回答", ResponseSanitizer.clean("<thought>private\ntext</thought>回答")); }
    @Test void stripsUnclosedReasoning() { assertEquals("", ResponseSanitizer.clean("<think>unfinished")); }
    @Test void handlesAttributesAndCase() { assertEquals("answer", ResponseSanitizer.clean("<THOUGHT mode='x'>hidden</THOUGHT>answer")); }
    @Test void stripsOrphanReasoningPrefix() { assertEquals("answer", ResponseSanitizer.clean("hidden</think>answer")); }
    @Test void preservesOrdinaryAnswer() { assertEquals("セラフィナです", ResponseSanitizer.clean("セラフィナです")); }
    @Test void ignoresExplicitThoughtPartButKeepsMixedFinalAnswer() {
        assertEquals("", ResponseSanitizer.visibleText("private text", true));
        assertEquals("33台", ResponseSanitizer.visibleText("<thought>hidden</thought>33台", true));
    }
}
