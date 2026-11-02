package com.tascode.codebridge.language.c;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.ir.FunctionDeclaration;
import com.tascode.codebridge.ir.EnumDeclaration;
import com.tascode.codebridge.ir.Program;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageParser;
import com.tascode.codebridge.language.subset.SubsetFunctionParser;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for the explicitly supported C function subset. */
public final class CParser implements LanguageParser {
    private static final Pattern GLOBAL_VARIABLE = Pattern.compile("\\b(?:int|long|short|char|float|double|_Bool|bool)\\s+[A-Za-z_][A-Za-z_0-9]*\\s*(?:\\[[^\\]]*\\])?\\s*(?:=[^;]*)?;");
    private static final Pattern FUNCTION_PROTOTYPE = Pattern.compile("\\b(?:void|char|short|int|long|float|double|_Bool|bool)\\s+[A-Za-z_][A-Za-z_0-9]*\\s*\\([^()]*\\)\\s*;");
    private static final List<UnsupportedSyntax> UNSUPPORTED = List.of(
            new UnsupportedSyntax(Pattern.compile("\\b(?:struct|union)\\b"), "CB-C-AGGREGATE-UNSUPPORTED",
                    "C structs and unions are not represented by the current CodeBridge IR."),
            new UnsupportedSyntax(Pattern.compile("\\b(?:const|volatile|register|extern|static|unsigned|signed|_Atomic)\\b"), "CB-C-QUALIFIER-UNSUPPORTED",
                    "C storage and integer qualifiers are not represented by the current CodeBridge IR and are rejected rather than dropped."),
            new UnsupportedSyntax(Pattern.compile("\\blong\\s+double\\b"), "CB-C-LONG-DOUBLE-UNSUPPORTED",
                    "C long double precision has no direct Java primitive equivalent."),
            new UnsupportedSyntax(Pattern.compile("\\btypedef\\b"), "CB-C-TYPEDEF-UNSUPPORTED",
                    "C typedef declarations are outside the supported subset; use a supported primitive type directly."),
            new UnsupportedSyntax(Pattern.compile("\\bswitch\\s*\\("), "CB-C-SWITCH-UNSUPPORTED",
                    "C switch statements are not supported yet; use if/else branches for equivalent control flow."),
            new UnsupportedSyntax(Pattern.compile("\\bgoto\\b"), "CB-C-GOTO-UNSUPPORTED",
                    "C goto statements and labels cannot be represented safely in the current IR."),
            new UnsupportedSyntax(Pattern.compile("\\b(?:__asm__|asm)\\b"), "CB-C-ASM-UNSUPPORTED",
                    "Inline assembly is compiler-specific and cannot be converted to Java."),
            new UnsupportedSyntax(Pattern.compile("\\b(?:void|char|short|int|long|float|double|_Bool|bool)\\s*\\*+"),
                    "CB-C-POINTER-UNSUPPORTED", "C pointers, pointer arithmetic, and pointer parameters are outside the supported subset."));
    private final SubsetFunctionParser delegate = new SubsetFunctionParser(Language.C);

    @Override public Language language() { return Language.C; }

    @Override public ParseResult parse(String source) {
        if (source == null || source.isBlank()) return delegate.parse(source);
        String syntax = maskCommentsAndLiterals(source);
        EnumExtraction extraction = extractEnums(source, syntax);
        if (extraction.diagnostic() != null) return new ParseResult(null, List.of(extraction.diagnostic()));
        source = extraction.source();
        syntax = maskCommentsAndLiterals(source);
        UnsupportedMatch unsupported = firstUnsupported(syntax);
        if (unsupported != null) {
            return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, unsupported.code(), unsupported.message(), location(source, unsupported.offset()))));
        }
        UnsupportedMatch global = firstGlobalVariable(syntax);
        if (global != null) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, global.code(), global.message(), location(source, global.offset()))));
        String withoutPrototypes = stripFunctionPrototypes(source);
        ParseResult result = delegate.parse(withoutPrototypes);
        if (!result.successful() || !(result.root() instanceof Program program)) return result;
        for (FunctionDeclaration function : program.functions()) {
            if (function.name().equals("main") && (!function.returnType().equals("int") || !function.parameters().isEmpty())) {
                return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-C-MAIN-UNSUPPORTED",
                        "Only a zero-argument int main() entry point is supported; command-line arguments and non-standard main signatures cannot be preserved.",
                        function.sourceSpan())));
            }
        }
        if (!extraction.enums().isEmpty()) {
            return new ParseResult(new Program(program.irVersion(), program.sourceSpan(), program.functions(),
                    program.includes(), program.globals(), extraction.enums()), result.diagnostics(), result.semanticDiagnostics());
        }
        return result;
    }

    private EnumExtraction extractEnums(String source, String syntax) {
        Pattern pattern = Pattern.compile("\\benum\\s+([A-Za-z_][A-Za-z_0-9]*)?\\s*\\{([^{}]*)}\\s*;", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(syntax);
        char[] rewritten = source.toCharArray();
        List<EnumDeclaration> enums = new java.util.ArrayList<>();
        java.util.Set<String> names = new java.util.HashSet<>();
        java.util.Set<String> tags = new java.util.HashSet<>();
        while (matcher.find()) {
            if (braceDepthAt(syntax, matcher.start()) != 0) return enumError(source, matcher.start(), "Enum definitions are supported only at file scope.");
            String tag = matcher.group(1) == null ? "" : matcher.group(1);
            if (!tag.isEmpty() && !tags.add(tag)) return enumError(source, matcher.start(), "Repeated enum tags are not supported by this subset.");
            List<EnumDeclaration.Enumerator> enumerators = new java.util.ArrayList<>();
            String body = matcher.group(2);
            int value = 0;
            int bodyOffset = matcher.start(2);
            for (String item : body.split(",", -1)) {
                String part = item.trim();
                if (part.isEmpty()) continue;
                Matcher entry = Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)(?:\\s*=\\s*([+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+)))?").matcher(part);
                if (!entry.matches()) return enumError(source, bodyOffset + body.indexOf(item), "Enum values must be implicit or explicit integer literals; expressions and scoped enumerators are not supported.");
                String name = entry.group(1);
                if (!names.add(name)) return enumError(source, bodyOffset + body.indexOf(item), "Enumerator '" + name + "' is defined more than once.");
                if (entry.group(2) != null) {
                    try { value = Integer.decode(entry.group(2)); }
                    catch (NumberFormatException exception) { return enumError(source, bodyOffset + body.indexOf(item), "Enum values must fit in a signed 32-bit integer."); }
                }
                int itemOffset = bodyOffset + body.indexOf(item) + Math.max(0, item.indexOf(name));
                enumerators.add(new EnumDeclaration.Enumerator(1, location(source, itemOffset), name, value));
                if (value == Integer.MAX_VALUE && !item.equals(body.substring(body.lastIndexOf(item)))) {
                    return enumError(source, itemOffset, "Implicit enum values must fit in a signed 32-bit integer.");
                }
                value++;
            }
            if (enumerators.isEmpty()) return enumError(source, matcher.start(), "An enum must declare at least one enumerator.");
            enums.add(new EnumDeclaration(1, location(source, matcher.start()), tag, enumerators));
            for (int i = matcher.start(); i < matcher.end(); i++) if (rewritten[i] != '\n' && rewritten[i] != '\r') rewritten[i] = ' ';
        }
        String lowered = new String(rewritten);
        if (!tags.isEmpty()) {
            Matcher tagUse = Pattern.compile("\\benum\\s+([A-Za-z_][A-Za-z_0-9]*)\\b").matcher(maskCommentsAndLiterals(lowered));
            rewritten = lowered.toCharArray();
            while (tagUse.find()) {
                if (!tags.contains(tagUse.group(1))) return enumError(source, tagUse.start(), "The enum tag is not defined in this source file.");
                for (int i = tagUse.start(); i < tagUse.end(); i++) if (rewritten[i] != '\n' && rewritten[i] != '\r') rewritten[i] = ' ';
                rewritten[tagUse.start() + 1] = 'i'; rewritten[tagUse.start() + 2] = 'n'; rewritten[tagUse.start() + 3] = 't';
            }
            lowered = new String(rewritten);
        }
        return new EnumExtraction(lowered, enums, null);
    }

    private EnumExtraction enumError(String source, int offset, String message) {
        return new EnumExtraction(source, List.of(), Diagnostic.at(Severity.ERROR, "CB-C-ENUM-UNSUPPORTED", message, location(source, offset)));
    }

    private UnsupportedMatch firstGlobalVariable(String syntax) {
        Matcher matcher = GLOBAL_VARIABLE.matcher(syntax);
        while (matcher.find()) {
            if (braceDepthAt(syntax, matcher.start()) == 0) {
                return new UnsupportedMatch("CB-C-GLOBAL-STATE-UNSUPPORTED",
                        "C global variables have program-wide mutable state that is not represented by the current function IR.", matcher.start());
            }
        }
        return null;
    }

    private int braceDepthAt(String syntax, int offset) {
        int depth = 0;
        for (int i = 0; i < offset; i++) {
            if (syntax.charAt(i) == '{') depth++;
            else if (syntax.charAt(i) == '}') depth--;
        }
        return depth;
    }
    private String stripFunctionPrototypes(String source) {
        char[] result = source.toCharArray();
        Matcher matcher = FUNCTION_PROTOTYPE.matcher(maskCommentsAndLiterals(source));
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                if (result[i] != '\n' && result[i] != '\r') result[i] = ' ';
            }
        }
        return new String(result);
    }
    private UnsupportedMatch firstUnsupported(String syntax) {
        UnsupportedMatch first = null;
        for (UnsupportedSyntax unsupported : UNSUPPORTED) {
            Matcher matcher = unsupported.pattern().matcher(syntax);
            if (matcher.find() && (first == null || matcher.start() < first.offset())) {
                first = new UnsupportedMatch(unsupported.code(), unsupported.message(), matcher.start());
            }
        }
        return first;
    }

    private String maskCommentsAndLiterals(String source) {
        char[] text = source.toCharArray();
        boolean lineComment = false, blockComment = false, escaped = false;
        char quote = 0;
        for (int i = 0; i < text.length; i++) {
            char c = text[i], next = i + 1 < text.length ? text[i + 1] : 0;
            if (lineComment) { if (c == 10) lineComment = false; else text[i] = ' '; continue; }
            if (blockComment) {
                if (c == '*' && next == '/') { text[i] = text[i + 1] = ' '; i++; blockComment = false; }
                else if (c != 10 && c != 13) text[i] = ' ';
                continue;
            }
            if (quote != 0) {
                if (c != 10 && c != 13) text[i] = ' ';
                if (escaped) escaped = false;
                else if (c == 92) escaped = true;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '/' && next == '/') { text[i] = text[i + 1] = ' '; i++; lineComment = true; continue; }
            if (c == '/' && next == '*') { text[i] = text[i + 1] = ' '; i++; blockComment = true; continue; }
            if (c == 34 || c == 39) { quote = c; text[i] = ' '; }
        }
        return new String(text);
    }

    private SourceSpan location(String source, int offset) {
        int line = 1, column = 1;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') { line++; column = 1; }
            else column++;
        }
        return SourceSpan.point(line, column);
    }

    private record UnsupportedSyntax(Pattern pattern, String code, String message) { }
    private record UnsupportedMatch(String code, String message, int offset) { }
    private record EnumExtraction(String source, List<EnumDeclaration> enums, Diagnostic diagnostic) { }
}
