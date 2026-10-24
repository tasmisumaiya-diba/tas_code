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
                String code = type.kind.equals("enum") ? "CB-CPP-ENUMS-UNSUPPORTED" : type.kind.equals("exception") ? "CB-CPP-EXCEPTIONS-UNSUPPORTED" : type.kind.equals("template") ? "CB-CPP-TEMPLATE-UNSUPPORTED"
                        : type.inherits ? "CB-CPP-INHERITANCE-UNSUPPORTED" : "CB-CPP-CLASS-UNSUPPORTED";
                String message = type.kind.equals("enum") ? "C++ enum declarations are outside the currently supported subset." : type.kind.equals("exception") ? "C++ exception handling is outside the currently supported subset." : type.kind.equals("template") ? "C++ templates are outside the supported subset."
                        : type.inherits ? "C++ class inheritance is outside the supported function-based subset."
                        : "C++ classes and structs are outside the supported function-based subset.";
                return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, code, message, SourceSpan.point(type.line, type.column))));
            }
        }
        return delegate.parse(source);
    }

    private UnsupportedType findUnsupportedType(String source) {
        int line = 1, column = 1;
        boolean lineComment = false, blockComment = false, escaped = false;
        char quote = 0;
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n') { lineComment = false; line++; column = 1; } else column++; i++; continue; }
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
                if (word.equals("enum")) return new UnsupportedType(line, column, false, "enum");
                if (java.util.Set.of("try", "catch", "throw").contains(word)) return new UnsupportedType(line, column, false, "exception");
                if (word.equals("template") && nextNonSpace(source, end) < source.length() && source.charAt(nextNonSpace(source, end)) == '<')
                    return new UnsupportedType(line, column, false, "template");
                if (java.util.Set.of("vector", "array", "map", "set", "pair", "tuple", "optional", "variant", "unordered_map", "unordered_set", "unique_ptr", "shared_ptr").contains(word)) {
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
    private int nextNonSpace(String source, int at) { while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++; return at; }
    private record UnsupportedType(int line, int column, boolean inherits, String kind) { }
}
