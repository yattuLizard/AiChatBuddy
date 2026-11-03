package io.github.mrgucci1.aiChatBuddy.util;

import java.util.regex.Pattern;

/** Removes explicit reasoning blocks before displaying or persisting public assistant text. */
public final class ResponseSanitizer {
    private static final Pattern CLOSED = Pattern.compile("<\\s*(thought|think)(?:\\s[^>]*)?>.*?<\\s*/\\s*\\1\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OPEN = Pattern.compile("<\\s*(?:thought|think)(?:\\s[^>]*)?>.*\\z", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ORPHAN_CLOSE = Pattern.compile("\\A.*?<\\s*/\\s*(?:thought|think)\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private ResponseSanitizer() {}
    public static String clean(String value) {
        if (value == null) return "";
        String text = CLOSED.matcher(value).replaceAll("");
        text = OPEN.matcher(text).replaceAll("");
        return ORPHAN_CLOSE.matcher(text).replaceAll("").strip();
    }
    public static String visibleText(String value, boolean thoughtPart) {
        if (!thoughtPart) return clean(value);
        // Some Gemma responses flag a mixed <thought>...</thought> + answer part as thought.
        if (value != null && Pattern.compile("</\\s*(?:thought|think)\\s*>", Pattern.CASE_INSENSITIVE).matcher(value).find())
            return clean(value);
        return "";
    }
}
