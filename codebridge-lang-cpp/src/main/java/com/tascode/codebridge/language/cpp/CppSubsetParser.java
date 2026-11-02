package com.tascode.codebridge.language.cpp;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageParser;
import com.tascode.codebridge.language.subset.SubsetFunctionParser;
import java.util.List;

/** C++ subset parser with explicit diagnostics for currently unsupported class constructs. */
public final class CppSubsetParser implements LanguageParser {
    private final SubsetFunctionParser delegate = new SubsetFunctionParser(Language.CPP);
    @Override public Language language() { return Language.CPP; }

    @Override public ParseResult parse(String source) {
        if (source != null) {
            UnsupportedType type = findUnsupportedType(source);
            if (type != null) {
                return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, diagnosticCode(type),
                        diagnosticMessage(type), SourceSpan.point(type.line, type.column))));
            }
            String masked = maskCommentsAndLiterals(source);
            java.util.regex.Matcher pointer = java.util.regex.Pattern.compile("\\b(?:void|bool|char|short|int|long|float|double|std::string)\\s*[*&]+\\s*[A-Za-z_][A-Za-z_0-9]*").matcher(masked);
            if (pointer.find()) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-CPP-POINTER-UNSUPPORTED",
                    "C++ pointers and lvalue/rvalue references are outside the supported subset.", location(source, pointer.start()))));
        }
        return delegate.parse(stripFunctionPrototypes(stripUsingNamespaceStd(source)));
    }
    private SourceSpan location(String source, int offset) {
        int line = 1, column = 1;
        for (int i = 0; i < offset; i++) { if (source.charAt(i) == '\n') { line++; column = 1; } else column++; }
        return SourceSpan.point(line, column);
    }    private String diagnosticCode(UnsupportedType type) {
        return switch (type.kind) {
            case "enum" -> "CB-CPP-ENUMS-UNSUPPORTED";
            case "exception" -> "CB-CPP-EXCEPTIONS-UNSUPPORTED";
            case "template" -> "CB-CPP-TEMPLATE-UNSUPPORTED";
            case "auto" -> "CB-CPP-AUTO-UNSUPPORTED";
            case "memory" -> "CB-CPP-MEMORY-UNSUPPORTED";
            case "cast" -> "CB-CPP-CAST-UNSUPPORTED";
            case "qualifier" -> "CB-CPP-QUALIFIER-UNSUPPORTED";
            default -> type.inherits ? "CB-CPP-INHERITANCE-UNSUPPORTED" : "CB-CPP-CLASS-UNSUPPORTED";
        };
    }
    private String diagnosticMessage(UnsupportedType type) {
        return switch (type.kind) {
            case "enum" -> "C++ enum declarations are outside the currently supported subset.";
            case "exception" -> "C++ exception handling is outside the currently supported subset.";
            case "template" -> "C++ templates are outside the supported subset.";
            case "auto" -> "C++ auto type deduction is outside the supported subset; declare a supported primitive type explicitly.";
            case "memory" -> "C++ dynamic allocation and nullptr pointer semantics are outside the supported subset.";
            case "cast" -> "C++ cast operators are outside the supported subset because their runtime and narrowing semantics cannot be preserved safely.";
            case "qualifier" -> "C++ qualifiers and constexpr semantics are outside the supported subset.";
            default -> type.inherits ? "C++ class inheritance is outside the supported function-based subset." : "C++ classes and structs are outside the supported function-based subset.";
        };
    }    private UnsupportedType findUnsupportedType(String source) {
        int line = 1, column = 1;
        boolean lineComment = false, blockComment = false, escaped = false;
        boolean atLineStart = true;
        char quote = 0;
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n') { lineComment = false; line++; column = 1; atLineStart = true; } else column++; i++; continue; }
            if (atLineStart && Character.isWhitespace(c) && c != '\n') { column++; i++; continue; }
            if (atLineStart && c == '#') {
                while (i < source.length() && source.charAt(i) != '\n') { i++; column++; }
                atLineStart = true;
                continue;
            }
            if (c == '\n') { line++; column = 1; atLineStart = true; i++; continue; }
            if (!Character.isWhitespace(c)) atLineStart = false;
            if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i += 2; column += 2; }
                else if (c == '\n') { i++; line++; column = 1; }
                else { i++; column++; }
                continue;
            }
            if (quote != 0) {
                if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0;
                if (c == '\n') { line++; column = 1; } else column++;
                i++; continue;
            }
            if (c == '/' && next == '/') { lineComment = true; i += 2; column += 2; continue; }
            if (c == '/' && next == '*') { blockComment = true; i += 2; column += 2; continue; }
            if (c == '"' || c == '\'') { quote = c; i++; column++; continue; }
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                String word = source.substring(i, end);
                if (word.equals("enum")) return new UnsupportedType(line, column, false, "enum");                if (word.equals("auto")) return new UnsupportedType(line, column, false, "auto");
                if (java.util.Set.of("new", "delete", "nullptr").contains(word)) return new UnsupportedType(line, column, false, "memory");
                if (java.util.Set.of("static_cast", "dynamic_cast", "reinterpret_cast", "const_cast").contains(word)) return new UnsupportedType(line, column, false, "cast");
                if (java.util.Set.of("const", "volatile", "constexpr", "thread_local").contains(word)) return new UnsupportedType(line, column, false, "qualifier");
                if (java.util.Set.of("try", "catch", "throw").contains(word)) return new UnsupportedType(line, column, false, "exception");
                if (word.equals("template") && nextNonSpace(source, end) < source.length() && source.charAt(nextNonSpace(source, end)) == '<')
                    return new UnsupportedType(line, column, false, "template");
                if (word.equals("vector")) {
                    int nextToken = nextNonSpace(source, end);
                    if (nextToken < source.length() && source.charAt(nextToken) == '<' && !isSupportedVector(source, i))
                        return new UnsupportedType(line, column, false, "template");
                }
                if (java.util.Set.of("array", "map", "set", "pair", "tuple", "optional", "variant", "unordered_map", "unordered_set", "unique_ptr", "shared_ptr").contains(word)) {
                    int nextToken = nextNonSpace(source, end);
                    if (nextToken < source.length() && source.charAt(nextToken) == '<') return new UnsupportedType(line, column, false, "template");
                }
                if (word.equals("class") || word.equals("struct")) {
                    int headerEnd = end;
                    while (headerEnd < source.length() && source.charAt(headerEnd) != '{' && source.charAt(headerEnd) != ';') headerEnd++;
                    boolean inherits = source.substring(end, headerEnd).contains(":");
                    return new UnsupportedType(line, column, inherits, "class");
                }
                column += end - i; i = end; continue;
            }
            if (c == '\n') { line++; column = 1; } else column++;
            i++;
        }
        return null;
    }
    private boolean isSupportedVector(String source, int vectorOffset) {
        int start = source.lastIndexOf("std", vectorOffset);
        if (start < 0) return false;
        java.util.regex.Pattern supported = java.util.regex.Pattern.compile(
                "std\\s*::\\s*vector\\s*<\\s*(?:int|long(?:\\s+long)?|short|char|float|double|bool|std\\s*::\\s*int8_t|std\\s*::\\s*string)\\s*>\\s*&?\\s*[A-Za-z_][A-Za-z_0-9]*\\s*(?:\\(|,|\\))");
        java.util.regex.Matcher matcher = supported.matcher(source);
        matcher.region(start, source.length());
        return matcher.lookingAt();
    }
    private String stripFunctionPrototypes(String source) {
        String syntax = maskCommentsAndLiterals(source);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?m)\\b(?:void|bool|char|short|int|long(?:\\s+long)?|float|double|std\\s*::\\s*(?:int8_t|string))\\s+[A-Za-z_][A-Za-z_0-9]*\\s*\\([^;{}]*\\)\\s*;").matcher(syntax);
        char[] normalized = source.toCharArray();
        while (matcher.find()) for (int i = matcher.start(); i < matcher.end(); i++) if (normalized[i] != '\r' && normalized[i] != '\n') normalized[i] = ' ';
        return new String(normalized);
    }
    private String stripUsingNamespaceStd(String source) {
        String syntax = maskCommentsAndLiterals(source);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?m)^[ \\t]*using[ \\t]+namespace[ \\t]+std[ \\t]*;[^\\r\\n]*").matcher(syntax);
        char[] normalized = source.toCharArray();
        while (matcher.find()) for (int i = matcher.start(); i < matcher.end(); i++) if (normalized[i] != '\r' && normalized[i] != '\n') normalized[i] = ' ';
        return new String(normalized);
    }
    private String maskCommentsAndLiterals(String source) {
        char[] chars = source.toCharArray(); boolean line = false, block = false, escaped = false; char quote = 0;
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i], next = i + 1 < chars.length ? chars[i + 1] : 0;
            if (line) { if (c == '\n') line = false; else chars[i] = ' '; continue; }
            if (block) { if (c == '*' && next == '/') { chars[i] = chars[i + 1] = ' '; i++; block = false; } else if (c != '\n' && c != '\r') chars[i] = ' '; continue; }
            if (quote != 0) { if (c != '\n' && c != '\r') chars[i] = ' '; if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0; continue; }
            if (c == '/' && next == '/') { chars[i] = chars[i + 1] = ' '; i++; line = true; }
            else if (c == '/' && next == '*') { chars[i] = chars[i + 1] = ' '; i++; block = true; }
            else if (c == '"' || c == '\'') { quote = c; chars[i] = ' '; }
        }
        return new String(chars);
    }    private int nextNonSpace(String source, int at) { while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++; return at; }
    private record UnsupportedType(int line, int column, boolean inherits, String kind) { }
}
