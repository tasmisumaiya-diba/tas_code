package com.tascode.codebridge.language.java;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageParser;
import com.tascode.codebridge.language.subset.SubsetFunctionParser;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for simple Java classes containing primitive static methods. */
public final class JavaParser implements LanguageParser {
    private static final Pattern CLASS_HEADER = Pattern.compile("(?s)^\\s*(?:public\\s+)?(?:final\\s+)?class\\s+[A-Za-z_$][\\w$]*\\s*\\{");
    private static final List<String> MODIFIERS = List.of("public", "private", "protected", "static", "final", "synchronized", "strictfp");
    private final SubsetFunctionParser functionParser = new SubsetFunctionParser(Language.JAVA);

    @Override public Language language() { return Language.JAVA; }

    @Override public ParseResult parse(String source) {
        if (source == null || source.isBlank()) {
            return new ParseResult(null, List.of(Diagnostic.withoutLocation(Severity.ERROR, "CB-PARSE-EMPTY", "Source code is empty.")));
        }
        try {
            String normalized = normalizeBooleanTypes(extractAndNormalizeClass(source));
            ParseResult result = functionParser.parse(normalized);
            if (result.successful()) return result;
            List<Diagnostic> diagnostics = result.diagnostics().stream()
                    .map(d -> new Diagnostic(d.severity(), "CB-PARSE-JAVA-UNSUPPORTED", d.message(), d.location()))
                    .toList();
            return new ParseResult(null, diagnostics);
        } catch (ParseFailure failure) {
            return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-PARSE-JAVA-UNSUPPORTED",
                    failure.getMessage(), failure.location)));
        }
    }

    private String extractAndNormalizeClass(String source) {
        Matcher header = CLASS_HEADER.matcher(source);
        if (!header.find()) throw new ParseFailure("Expected one simple class declaration", SourceSpan.point(1, 1));
        int open = header.end() - 1;
        int close = matchingClose(source, open);
        if (!source.substring(close + 1).isBlank()) throw new ParseFailure("Only one top-level class is supported", location(source, close + 1));
        char[] normalized = source.toCharArray();
        for (int i = 0; i <= open; i++) if (normalized[i] != '\n' && normalized[i] != '\r') normalized[i] = ' ';
        for (int i = close; i < normalized.length; i++) if (normalized[i] != '\n' && normalized[i] != '\r') normalized[i] = ' ';
        stripMethodModifiers(normalized, open + 1, close);
        return new String(normalized);
    }

    private int matchingClose(String source, int open) {
        int depth = 1;
        char quote = 0;
        boolean lineComment = false, blockComment = false, escaped = false;
        for (int i = open + 1; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (quote != 0) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0; continue; }
            if (c == '/' && next == '/') { lineComment = true; i++; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return i;
        }
        throw new ParseFailure("Unclosed class body", location(source, open));
    }

    private void stripMethodModifiers(char[] text, int start, int end) {
        int depth = 1;
        char quote = 0;
        boolean lineComment = false, blockComment = false, escaped = false;
        for (int i = start; i < end; ) {
            char c = text[i], next = i + 1 < end ? text[i + 1] : 0;
            if (lineComment) { if (c == '\n') lineComment = false; i++; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i += 2; } else i++; continue; }
            if (quote != 0) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0; i++; continue; }
            if (c == '/' && next == '/') { lineComment = true; i += 2; continue; }
            if (c == '/' && next == '*') { blockComment = true; i += 2; continue; }
            if (c == '"' || c == '\'') { quote = c; i++; continue; }
            if (c == '{') { depth++; i++; continue; }
            if (c == '}') { depth--; i++; continue; }
            if (depth == 1 && Character.isJavaIdentifierStart(c)) {
                int tokenEnd = i + 1;
                while (tokenEnd < end && Character.isJavaIdentifierPart(text[tokenEnd])) tokenEnd++;
                String token = new String(text, i, tokenEnd - i);
                if (MODIFIERS.contains(token)) {
                    for (int j = i; j < tokenEnd; j++) if (text[j] != '\n' && text[j] != '\r') text[j] = ' ';
                }
                i = tokenEnd;
            } else i++;
        }
    }

    private String normalizeBooleanTypes(String source) {
        char[] text = source.toCharArray();
        char quote = 0;
        boolean lineComment = false, blockComment = false, escaped = false;
        for (int i = 0; i < text.length; ) {
            char c = text[i], next = i + 1 < text.length ? text[i + 1] : 0;
            if (lineComment) { if (c == '\n') lineComment = false; i++; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i += 2; } else i++; continue; }
            if (quote != 0) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0; i++; continue; }
            if (c == '/' && next == '/') { lineComment = true; i += 2; continue; }
            if (c == '/' && next == '*') { blockComment = true; i += 2; continue; }
            if (c == '"' || c == '\'') { quote = c; i++; continue; }
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < text.length && Character.isJavaIdentifierPart(text[end])) end++;
                if (end - i == 7 && new String(text, i, 7).equals("boolean")) {
                    text[i] = 'b'; text[i + 1] = 'o'; text[i + 2] = 'o'; text[i + 3] = 'l';
                    for (int j = i + 4; j < end; j++) text[j] = ' ';
                }
                i = end;
            } else i++;
        }
        return new String(text);
    }

    private SourceSpan location(String source, int offset) {
        int line = 1, column = 1;
        for (int i = 0; i < Math.min(offset, source.length()); i++) { if (source.charAt(i) == '\n') { line++; column = 1; } else column++; }
        return SourceSpan.point(line, column);
    }

    private static final class ParseFailure extends RuntimeException {
        private final SourceSpan location;
        private ParseFailure(String message, SourceSpan location) { super(message); this.location = location; }
    }
}

