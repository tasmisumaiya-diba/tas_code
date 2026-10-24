package com.tascode.codebridge.preprocess;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.language.Language;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Handles the deliberately limited C/C++ preprocessor subset before grammar parsing. */
public final class CPreprocessor {
    private static final Set<String> SUPPORTED_HEADERS = Set.of("stdio.h", "stdbool.h", "stdlib.h", "string.h", "math.h", "iostream", "string");
    private static final Pattern INCLUDE = Pattern.compile("#\\s*include\\s*<([A-Za-z0-9_.]+)>\\s*");
    private static final Pattern DEFINE = Pattern.compile("#\\s*define\\s+([A-Za-z_][A-Za-z_0-9]*)(?:\\s+(.*))?\\s*");
    private static final Pattern OBJECT_MACRO_VALUE = Pattern.compile("(?:[+-]?\\d+|true|false|\"[^\"\\n]*\")");

    public PreprocessResult process(Language language, String source) {
        if (language != Language.C && language != Language.CPP) return new PreprocessResult(source, List.of(), List.of());
        if (source == null) return new PreprocessResult(null, List.of(), List.of());
        String[] lines = source.split("(?<=\\n)", -1);
        StringBuilder output = new StringBuilder(source.length());
        LinkedHashSet<String> includes = new LinkedHashSet<>();
        Map<String, String> macros = new LinkedHashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        boolean[] blockComment = {false};
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String content = stripLineEnding(line);
            int directive = directiveIndex(content, blockComment[0]);
            if (directive < 0) { output.append(expand(line, macros, diagnostics, i + 1, blockComment)); continue; }
            String trimmed = content.substring(directive);
            int column = directive + 1;
            updateBlockComment(content, blockComment);
            int lineNumber = i + 1;
            Matcher include = INCLUDE.matcher(trimmed);
            if (include.matches()) {
                String header = include.group(1);
                if (SUPPORTED_HEADERS.contains(header)) includes.add(header);
                else diagnostics.add(Diagnostic.at(com.tascode.codebridge.diagnostic.Severity.ERROR, "CB-PREPROC-UNSUPPORTED-INCLUDE", "Header '" + header + "' is not supported by the preprocessor.", SourceSpan.point(lineNumber, column)));
                output.append(blank(line));
                continue;
            }
            Matcher define = DEFINE.matcher(trimmed);
            if (define.matches()) {
                String name = define.group(1), value = define.group(2);
                if (value == null || !OBJECT_MACRO_VALUE.matcher(value.strip()).matches()) {
                    diagnostics.add(Diagnostic.at(com.tascode.codebridge.diagnostic.Severity.ERROR, "CB-PREPROC-UNSUPPORTED-MACRO", "Only simple object-like literal macros are recognized; macro '" + name + "' is unsupported.", SourceSpan.point(lineNumber, column)));
                } else if (macros.containsKey(name) && !macros.get(name).equals(value.strip())) diagnostics.add(Diagnostic.at(com.tascode.codebridge.diagnostic.Severity.ERROR, "CB-PREPROC-MACRO-REDEFINED", "Macro '" + name + "' is redefined with a different value.", SourceSpan.point(lineNumber, column)));
                else macros.put(name, value.strip());
                output.append(blank(line));
                continue;
            }
            diagnostics.add(Diagnostic.at(com.tascode.codebridge.diagnostic.Severity.ERROR, "CB-PREPROC-UNSUPPORTED-DIRECTIVE", "This preprocessor directive is not supported.", SourceSpan.point(lineNumber, column)));
            output.append(blank(line));
        }
        return new PreprocessResult(output.toString(), List.copyOf(includes), diagnostics);
    }

    private String expand(String line, Map<String, String> macros, List<Diagnostic> diagnostics, int lineNumber, boolean[] blockComment) {
        StringBuilder output = new StringBuilder(line.length());
        char quote = 0;
        boolean escaped = false, lineComment = false;
        for (int i = 0; i < line.length();) {
            char c = line.charAt(i), next = i + 1 < line.length() ? line.charAt(i + 1) : 0;
            if (lineComment) { output.append(c); i++; continue; }
            if (blockComment[0]) { output.append(c); if (c == '*' && next == '/') { output.append(next); i += 2; blockComment[0] = false; } else i++; continue; }
            if (quote != 0) { output.append(c); if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0; i++; continue; }
            if (c == '/' && next == '/') { output.append("//"); i += 2; lineComment = true; continue; }
            if (c == '/' && next == '*') { output.append("/*"); i += 2; blockComment[0] = true; continue; }
            if (c == '"' || c == '\'') { quote = c; output.append(c); i++; continue; }
            if (Character.isLetter(c) || c == '_') {
                int end = i + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                String token = line.substring(i, end), value = macros.get(token);
                if (value == null) output.append(token);
                else if (value.length() > token.length()) {
                    diagnostics.add(Diagnostic.at(com.tascode.codebridge.diagnostic.Severity.ERROR, "CB-PREPROC-MACRO-EXPANSION-LENGTH", "Macro '" + token + "' expands to more columns than its source token; this expansion is not supported.", SourceSpan.point(lineNumber, i + 1)));
                    output.append(token);
                } else output.append(value).append(" ".repeat(token.length() - value.length()));
                i = end;
            } else { output.append(c); i++; }
        }
        return output.toString();
    }

    private int directiveIndex(String line, boolean insideBlockComment) {
        for (int i = 0; i < line.length();) {
            char c = line.charAt(i), next = i + 1 < line.length() ? line.charAt(i + 1) : 0;
            if (insideBlockComment) { if (c == '*' && next == '/') { insideBlockComment = false; i += 2; } else i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && next == '*') { insideBlockComment = true; i += 2; continue; }
            if (c == '/' && next == '/') return -1;
            return c == '#' ? i : -1;
        }
        return -1;
    }

    private void updateBlockComment(String line, boolean[] state) {
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < line.length();) {
            char c = line.charAt(i), next = i + 1 < line.length() ? line.charAt(i + 1) : 0;
            if (state[0]) { if (c == '*' && next == '/') { state[0] = false; i += 2; } else i++; continue; }
            if (quote != 0) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0; i++; continue; }
            if (c == '/' && next == '/') return;
            if (c == '/' && next == '*') { state[0] = true; i += 2; continue; }
            if (c == '"' || c == '\'') quote = c;
            i++;
        }
    }

    private String stripLineEnding(String line) { return line.endsWith("\n") ? line.substring(0, line.length() - 1).replaceFirst("\\r$", "") : line; }
    private String blank(String line) { return line.replaceAll("[^\\r\\n]", " "); }
}
