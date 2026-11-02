package com.tascode.codebridge.language.java;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageParser;
import com.tascode.codebridge.language.subset.SubsetFunctionParser;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser for simple Java classes containing primitive static methods. */
public final class JavaParser implements LanguageParser {
    private static final Pattern CLASS_HEADER = Pattern.compile("(?s)^\\s*(?:public\\s+)?(?:final\\s+)?class\\s+[A-Za-z_$][\\w$]*\\s*\\{");
    private static final Pattern INHERITANCE_HEADER = Pattern.compile("(?s)^\\s*(?:public\\s+)?(?:abstract\\s+)?class\\s+[A-Za-z_$][\\w$]*\\s+(?:extends|implements)\\b");
    private static final Pattern INTERFACE_HEADER = Pattern.compile("(?s)^\\s*(?:public\\s+)?interface\\s+[A-Za-z_$][\\w$]*\\b");
    private static final Pattern ENUM_HEADER = Pattern.compile("(?s)^\\s*(?:public\\s+)?enum\\s+[A-Za-z_$][\\w$]*\\b");
    private static final Pattern GENERIC_SYNTAX = Pattern.compile("\\b(?:class\\s+[A-Za-z_$][\\w$]*|List|ArrayList|Map|HashMap|Set|HashSet|Collection|Optional|Queue|Deque)\\s*<");
    private static final Pattern EXCEPTION_SYNTAX = Pattern.compile("\\b(?:try|catch|throw|throws)\\b");
    private static final List<UnsupportedJavaSyntax> UNSUPPORTED_SYNTAX = List.of(
            new UnsupportedJavaSyntax(Pattern.compile("(?m)^\\s*(?:package|import)\\b"), "CB-JAVA-IMPORT-UNSUPPORTED", "Java packages and imports are outside the supported single-class subset."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bfor\\s*\\([^;()]+:\\s*[^)]+\\)"), "CB-JAVA-ENHANCED-FOR-UNSUPPORTED", "Java enhanced-for loops require iterable and iterator semantics that are not represented in the current IR."),
            new UnsupportedJavaSyntax(Pattern.compile("\\(\\s*(?:byte|short|int|long|float|double|char|boolean|String)\\s*\\)\\s*(?=[\\w(])"), "CB-JAVA-CAST-UNSUPPORTED", "Java casts are outside the supported subset because narrowing and reference-cast behavior cannot be preserved generally."),
            new UnsupportedJavaSyntax(Pattern.compile("\\b(?:this|super)\\b"), "CB-JAVA-INSTANCE-REFERENCE-UNSUPPORTED", "Java this/super references require object state and dispatch semantics that the function IR does not represent."),
            new UnsupportedJavaSyntax(Pattern.compile("->"), "CB-JAVA-LAMBDA-UNSUPPORTED", "Java lambda expressions cannot be represented in the current function IR."),
            new UnsupportedJavaSyntax(Pattern.compile("::"), "CB-JAVA-METHOD-REFERENCE-UNSUPPORTED", "Java method references cannot be represented in the current function IR."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bnew\\s+(?!byte\\b|short\\b|int\\b|long\\b|float\\b|double\\b|char\\b|boolean\\b|String\\b|java\\.lang\\.String\\b)[A-Za-z_$]"), "CB-JAVA-OBJECT-CREATION-UNSUPPORTED", "Java object and array creation are outside the supported subset; use supported fixed-size array declarations."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bchar\\s*\\[\\s*\\]"), "CB-JAVA-ARRAY-ELEMENT-UNSUPPORTED", "Java char arrays use UTF-16 elements and cannot be represented by the current C++ char-array mapping."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bnull\\b"), "CB-JAVA-NULL-UNSUPPORTED", "Java null references have no supported C++ representation in this subset."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bvar\\b"), "CB-JAVA-VAR-UNSUPPORTED", "Java local-variable type inference is outside the supported subset; declare the variable type explicitly."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bswitch\\s*\\("), "CB-JAVA-SWITCH-UNSUPPORTED", "Java switch statements and expressions are outside the current IR subset."),
            new UnsupportedJavaSyntax(Pattern.compile("@[A-Za-z_$][\\w$]*"), "CB-JAVA-ANNOTATION-UNSUPPORTED", "Java annotations are not preserved by the current C++ generator."),
            new UnsupportedJavaSyntax(Pattern.compile("\\b(?:record|sealed|permits|native)\\b"), "CB-JAVA-MODERN-TYPE-UNSUPPORTED", "Java records, sealed declarations, and native methods are outside the supported subset."),
            new UnsupportedJavaSyntax(Pattern.compile("\\[\\s*\\]\\s*\\[\\s*\\]"), "CB-JAVA-MULTIDIMENSIONAL-ARRAY-UNSUPPORTED", "Only one-dimensional Java arrays are represented in the shared IR."));
    private static final List<String> MODIFIERS = List.of("public", "static", "strictfp");
    private final SubsetFunctionParser functionParser = new SubsetFunctionParser(Language.JAVA);
    private final JavaAstFrontend frontend = new JavaAstFrontend();

    @Override public Language language() { return Language.JAVA; }

    @Override public ParseResult parse(String source) {
        if (source == null || source.isBlank()) {
            return new ParseResult(null, List.of(Diagnostic.withoutLocation(Severity.ERROR, "CB-PARSE-EMPTY", "Source code is empty.")));
        }
        JavaAstFrontend.Result frontendResult = frontend.parse(source);
        if (frontendResult.model().isEmpty()) return new ParseResult(null, frontendResult.diagnostics());
        FieldExtraction fieldExtraction = extractPrimitiveStaticFields(source, frontendResult.model().orElseThrow());
        if (!fieldExtraction.diagnostics().isEmpty()) return new ParseResult(null, fieldExtraction.diagnostics());
        source = fieldExtraction.source();
        String syntax = maskCommentsAndLiterals(source);
        for (UnsupportedJavaSyntax unsupported : UNSUPPORTED_SYNTAX) {
            Matcher matcher = unsupported.pattern().matcher(syntax);
            if (matcher.find()) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, unsupported.code(),
                    unsupported.message(), location(source, matcher.start()))));
        }
        Matcher stringArray = Pattern.compile("\\b(?:String|java\\.lang\\.String)\\s*\\[\\s*\\]").matcher(syntax);
        while (stringArray.find()) {
            if (Pattern.compile("\\bmain\\s*\\(\\s*$").matcher(syntax.substring(0, stringArray.start())).find()) continue;
            if (hasNonNullStringArrayInitializer(source, stringArray.end())) continue;
            return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-ARRAY-ELEMENT-UNSUPPORTED",
                    "String arrays need a literal initializer list so Java null elements are not lost in C++.",
                    location(source, stringArray.start()))));
        }
        Matcher inheritance = INHERITANCE_HEADER.matcher(syntax);
        if (inheritance.find()) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-INHERITANCE-UNSUPPORTED",
                "Java extends and implements declarations are outside the supported static-function subset.", location(source, inheritance.start()))));
        Matcher interfaceHeader = INTERFACE_HEADER.matcher(syntax);
        if (interfaceHeader.find()) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-INTERFACE-UNSUPPORTED",
                "Java interfaces are outside the supported static-function subset.", location(source, interfaceHeader.start()))));
        Matcher restrictedAccess = Pattern.compile("\\b(?:private|protected)\\b").matcher(syntax);
        if (restrictedAccess.find()) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-ACCESS-UNSUPPORTED",
                "Private and protected members cannot be flattened without changing their visibility.", location(source, restrictedAccess.start()))));
        Matcher synchronizedMethod = Pattern.compile("\\bsynchronized\\b").matcher(syntax);
        if (synchronizedMethod.find()) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-THREADING-UNSUPPORTED",
                "Synchronized Java code is outside the supported subset because thread-safety semantics cannot be preserved.", location(source, synchronizedMethod.start()))));
        Matcher exception = EXCEPTION_SYNTAX.matcher(maskCommentsAndLiterals(source));
        if (exception.find()) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-EXCEPTIONS-UNSUPPORTED",
                "Java try/catch/throw is outside the currently supported subset.", location(source, exception.start()))));
        Matcher enumHeader = ENUM_HEADER.matcher(syntax);
        if (enumHeader.find()) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-ENUMS-UNSUPPORTED",
                "Java enums are outside the supported static-function subset.", location(source, enumHeader.start()))));
        int genericAt = findGenericSyntax(source);
        if (genericAt >= 0) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-GENERICS-UNSUPPORTED",
                "Java generics and parameterized collection types are outside the supported subset.", location(source, genericAt))));
        try {
            String normalized = normalizeBooleanTypes(normalizeJavaEntryPoint(extractAndNormalizeClass(source)));
            ParseResult result = functionParser.parse(normalized);
            if (result.successful() && result.root() instanceof com.tascode.codebridge.ir.Program program)
                return new ParseResult(new com.tascode.codebridge.ir.Program(program.irVersion(), program.sourceSpan(), program.functions(), program.includes(), fieldExtraction.globals(), List.of(), fieldExtraction.classes()), result.diagnostics(), frontendResult.diagnostics());
            if (fieldExtraction.classes().size() > 0 && isTrivia(normalized))
                return new ParseResult(new com.tascode.codebridge.ir.Program(1, SourceSpan.point(1, 1), List.of(), List.of(), fieldExtraction.globals(), List.of(), fieldExtraction.classes()), List.of(), frontendResult.diagnostics());
            List<Diagnostic> diagnostics = result.diagnostics().stream()
                    .map(d -> new Diagnostic(d.severity(), "CB-PARSE-JAVA-UNSUPPORTED", d.message(), d.location()))
                    .toList();
            return new ParseResult(null, diagnostics);
        } catch (ParseFailure failure) {
            return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, failure.code,
                    failure.getMessage(), failure.location)));
        }
    }

    private FieldExtraction extractPrimitiveStaticFields(String source, JavaFrontendModel model) {
        if (model.types().isEmpty() || !model.types().getFirst().kind().equals("CLASS"))
            return new FieldExtraction(source, List.of(), List.of(), List.of());
        List<com.tascode.codebridge.ir.GlobalVariableDeclaration> globals = new java.util.ArrayList<>();
        List<com.tascode.codebridge.ir.FieldDeclaration> instanceFields = new java.util.ArrayList<>();
        char[] rewritten = source.toCharArray();
        for (JavaFrontendModel.MemberModel member : model.types().getFirst().members()) {
            if (!member.kind().equals("FIELD")) continue;
            if (member.type().isEmpty()) continue;
            com.tascode.codebridge.ir.TypeReference type = member.type().orElseThrow();
            if (type.arrayDimensions() != 0 || !type.typeArguments().isEmpty()
                    || !List.of("byte", "short", "int", "long", "float", "double", "char", "boolean").contains(type.name()))
                return new FieldExtraction(source, List.of(), List.of(), List.of(Diagnostic.at(Severity.ERROR,
                        type.arrayDimensions() > 0 && !member.modifiers().contains("static") ? "CB-JAVA-CLASS-STATE-UNSUPPORTED"
                                : member.modifiers().contains("static") ? "CB-JAVA-STATIC-FIELD-UNSUPPORTED" : "CB-JAVA-FIELD-UNSUPPORTED",
                        "Only scalar primitive fields are supported in Java data classes and static state.", member.sourceSpan())));
            if (member.modifiers().contains("final") && member.initializerSource().isEmpty()) return new FieldExtraction(source, List.of(), List.of(), List.of(Diagnostic.at(Severity.ERROR,
                    "CB-JAVA-FINAL-FIELD-UNINITIALIZED", "A final primitive field needs an explicit literal initializer.", member.sourceSpan())));
            Optional<com.tascode.codebridge.ir.Expression> initializer = Optional.empty();
            if (member.initializerSource().isPresent()) {
                String literalSource = member.initializerSource().orElseThrow();
                com.tascode.codebridge.ir.Expression literal = parseStaticFieldLiteral(type.name(), literalSource, member.sourceSpan());
                if (literal == null || !isStaticFieldLiteralCompatible(type.name(), literal)) return new FieldExtraction(source, List.of(), List.of(), List.of(Diagnostic.at(Severity.ERROR,
                        "CB-JAVA-STATIC-FIELD-INITIALIZER-UNSUPPORTED", "Static field initializers must be primitive literals so Java class initialization order is not changed.", member.sourceSpan())));
                initializer = Optional.of(literal);
            }
            String normalizedType = type.name().equals("boolean") ? "bool" : type.name();
            if (member.modifiers().contains("static")) {
                String visibility = member.modifiers().contains("public") ? "public"
                        : member.modifiers().contains("private") ? "private"
                        : member.modifiers().contains("protected") ? "protected" : "";
                globals.add(new com.tascode.codebridge.ir.GlobalVariableDeclaration(1, member.sourceSpan(), member.name(), normalizedType,
                        initializer, member.modifiers().contains("final"), visibility));
            } else {
                if (!member.modifiers().contains("public") && !member.modifiers().contains("private")
                        && !member.modifiers().contains("protected")) return new FieldExtraction(source, List.of(), List.of(), List.of(Diagnostic.at(
                        Severity.ERROR, "CB-JAVA-CLASS-STATE-UNSUPPORTED",
                        "Package-private fields have no direct C++ access equivalent; declare the field public, protected, or private.", member.sourceSpan())));
                instanceFields.add(new com.tascode.codebridge.ir.FieldDeclaration(1, member.sourceSpan(), member.name(),
                        new com.tascode.codebridge.ir.TypeReference(1, member.sourceSpan(), normalizedType), member.modifiers(), initializer));
            }
            int start = offsetAt(source, member.sourceSpan().startLine(), member.sourceSpan().startColumn());
            while (start > 0 && source.charAt(start - 1) != ';' && source.charAt(start - 1) != '{' && source.charAt(start - 1) != '}') start--;
            int end = offsetAt(source, member.sourceSpan().endLine(), member.sourceSpan().endColumn()) + 1;
            while (end < source.length() && Character.isWhitespace(source.charAt(end))) end++;
            if (end < source.length() && source.charAt(end) == ';') end++;
            for (int i = start; i < end; i++) if (rewritten[i] != '\n' && rewritten[i] != '\r') rewritten[i] = ' ';
        }
        List<com.tascode.codebridge.ir.ClassDeclaration> classes = List.of();
        if (!instanceFields.isEmpty()) {
            JavaFrontendModel.TypeModel type = model.types().getFirst();
            if (!type.modifiers().contains("public")) return new FieldExtraction(source, List.of(), List.of(), List.of(Diagnostic.at(
                    Severity.ERROR, "CB-JAVA-PACKAGE-ACCESS-UNSUPPORTED",
                    "Package-private data classes have no direct C++ access equivalent; declare the class public.", SourceSpan.point(1, 1))));
            String visibility = type.modifiers().contains("public") ? "public" : type.modifiers().contains("private") ? "private" : "";
            classes = List.of(new com.tascode.codebridge.ir.ClassDeclaration(1, SourceSpan.point(1, 1), type.name(),
                    com.tascode.codebridge.ir.ClassDeclaration.Kind.CLASS, type.modifiers(), visibility, List.of(), Optional.empty(),
                    List.of(), instanceFields, List.of(), List.of(), List.of()));
        }
        return new FieldExtraction(new String(rewritten), globals, classes, List.of());
    }

    private com.tascode.codebridge.ir.Expression parseStaticFieldLiteral(String type, String value, SourceSpan span) {
        ParseResult parsed = functionParser.parse(normalizeBooleanTypes(type + " __codebridge_field() { return " + value + "; }"));
        if (!parsed.successful() || !(parsed.root() instanceof com.tascode.codebridge.ir.Program program)) return null;
        com.tascode.codebridge.ir.Statement statement = program.functions().getFirst().body().statements().getFirst();
        if (!(statement instanceof com.tascode.codebridge.ir.ReturnStatement returned) || returned.value().isEmpty()
                || !(returned.value().orElseThrow() instanceof com.tascode.codebridge.ir.LiteralExpression literal)) return null;
        return new com.tascode.codebridge.ir.LiteralExpression(1, span, literal.value());
    }

    private boolean isStaticFieldLiteralCompatible(String type, com.tascode.codebridge.ir.Expression expression) {
        if (!(expression instanceof com.tascode.codebridge.ir.LiteralExpression literal)) return false;
        Object value = literal.value();
        if (type.equals("boolean")) return value instanceof Boolean;
        if (type.equals("char")) {
            if (value instanceof Character) return true;
            return value instanceof Integer number && number >= 0 && number <= Character.MAX_VALUE;
        }
        if (type.equals("byte") && value instanceof Integer number)
            return number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE;
        if (type.equals("short") && value instanceof Integer number)
            return number >= Short.MIN_VALUE && number <= Short.MAX_VALUE;
        if (type.equals("int")) return value instanceof Integer || value instanceof Character;
        if (type.equals("long")) return value instanceof Integer || value instanceof Long || value instanceof Character;
        if (type.equals("float")) return value instanceof Integer || value instanceof Long || value instanceof Float || value instanceof Character;
        return type.equals("double") && (value instanceof Number || value instanceof Character);
    }

    private int offsetAt(String source, int line, int column) {
        int currentLine = 1, offset = 0;
        while (offset < source.length() && currentLine < line) if (source.charAt(offset++) == '\n') currentLine++;
        return Math.min(source.length(), offset + column - 1);
    }

    private record FieldExtraction(String source, List<com.tascode.codebridge.ir.GlobalVariableDeclaration> globals,
                                   List<com.tascode.codebridge.ir.ClassDeclaration> classes,
                                   List<Diagnostic> diagnostics) { }
    private boolean hasNonNullStringArrayInitializer(String source, int afterType) {
        Matcher declaration = Pattern.compile("^\\s*(?:[A-Za-z_$][\\w$]*\\s*=\\s*(?:new\\s+(?:java\\.lang\\.)?String\\s*\\[\\s*\\]\\s*)?)?\\{([^{}]*)\\}")
                .matcher(source.substring(afterType));
        if (!declaration.find()) return false;
        String values = declaration.group(1);
        return Pattern.matches("\\s*(?:\"(?:\\\\.|[^\"\\\\])*\"\\s*(?:,\\s*\"(?:\\\\.|[^\"\\\\])*\"\\s*)*)?\\s*", values);
    }
    private int findGenericSyntax(String source) {
        Matcher matcher = GENERIC_SYNTAX.matcher(maskCommentsAndLiterals(source));
        return matcher.find() ? matcher.end() - 1 : -1;
    }

    private String maskCommentsAndLiterals(String source) {
        char[] text = source.toCharArray();
        boolean lineComment = false, blockComment = false, escaped = false;
        char quote = 0;
        for (int i = 0; i < text.length; i++) {
            char c = text[i], next = i + 1 < text.length ? text[i + 1] : 0;
            if (lineComment) { if (c == '\n') lineComment = false; else text[i] = ' '; continue; }
            if (blockComment) { if (c == '*' && next == '/') { text[i] = text[i + 1] = ' '; i++; blockComment = false; } else if (c != '\n' && c != '\r') text[i] = ' '; continue; }
            if (quote != 0) { if (c != '\n' && c != '\r') text[i] = ' '; if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0; continue; }
            if (c == '/' && next == '/') { text[i] = text[i + 1] = ' '; i++; lineComment = true; continue; }
            if (c == '/' && next == '*') { text[i] = text[i + 1] = ' '; i++; blockComment = true; continue; }
            if (c == '"' || c == '\'') { quote = c; text[i] = ' '; }
        }
        return new String(text);
    }
    private String extractAndNormalizeClass(String source) {
        String syntax = maskCommentsAndLiterals(source);
        Matcher header = CLASS_HEADER.matcher(syntax);
        if (!header.find()) throw new ParseFailure("Expected one simple class declaration", SourceSpan.point(1, 1));
        int open = header.end() - 1;
        if (!isTrivia(source.substring(0, header.start()))) throw new ParseFailure("Only one top-level class is supported", location(source, 0));
        int close = matchingClose(source, open);
        validateStaticMethods(source, open + 1, close);
        if (!isTrivia(source.substring(close + 1))) throw new ParseFailure("Only one top-level class is supported", location(source, close + 1));
        char[] normalized = source.toCharArray();
        for (int i = 0; i <= open; i++) if (normalized[i] != '\n' && normalized[i] != '\r') normalized[i] = ' ';
        for (int i = close; i < normalized.length; i++) if (normalized[i] != '\n' && normalized[i] != '\r') normalized[i] = ' ';
        stripFinalMethodModifiers(normalized, open + 1, close);
        stripMethodModifiers(normalized, open + 1, close);
        return new String(normalized);
    }

    private String normalizeJavaEntryPoint(String source) {
        Pattern launcher = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:java\\.lang\\.)?String\\s*\\[\\s*\\]\\s*([A-Za-z_$][\\w$]*)\\s*\\)");
        Matcher matcher = launcher.matcher(maskCommentsAndLiterals(source));
        if (!matcher.find()) return source;
        int headerStart = matcher.start();
        int headerEnd = matcher.end();
        String argumentName = matcher.group(1);
        if (matcher.find()) throw new ParseFailure("Only one Java main(String[] args) entry point can be converted to C.", location(source, matcher.start()));
        int signatureStart = source.lastIndexOf("void", headerStart);
        if (signatureStart < 0) throw new ParseFailure("Invalid Java main entry point.", location(source, headerStart));
        int openBrace = source.indexOf('{', headerEnd);
        int closeBrace = openBrace < 0 ? -1 : matchingClose(source, openBrace);
        if (closeBrace < 0) throw new ParseFailure("Unclosed Java main body.", location(source, headerStart));
        String body = source.substring(openBrace + 1, closeBrace);
        if (Pattern.compile("\\b" + Pattern.quote(argumentName) + "\\b").matcher(maskCommentsAndLiterals(body)).find()) {
            throw new ParseFailure("Using command-line arguments in Java main is not supported when converting to C.", location(source, headerStart));
        }
        String normalizedBody = normalizeBareReturns(normalizeSystemExitReturns(body));
        String completion = hasTerminalReturn(normalizedBody) ? "" : "\nreturn 0;\n";
        return source.substring(0, signatureStart) + "int main()" + source.substring(headerEnd, openBrace + 1)
                + normalizedBody + completion + source.substring(closeBrace);
    }

    private boolean hasTerminalReturn(String body) {
        String syntax = maskCommentsAndLiterals(body).stripTrailing();
        return Pattern.compile("\\breturn\\s+[^;]+;\\s*$").matcher(syntax).find();
    }

    private String normalizeSystemExitReturns(String body) {
        String syntax = maskCommentsAndLiterals(body);
        Pattern exitCall = Pattern.compile("\\bSystem\\s*\\.\\s*exit\\s*\\(");
        Matcher matcher = exitCall.matcher(syntax);
        StringBuilder normalized = new StringBuilder();
        int copied = 0;
        int scan = 0;
        while (matcher.find(scan)) {
            int argumentStart = matcher.end();
            int depth = 1;
            int close = argumentStart;
            while (close < syntax.length() && depth > 0) {
                char current = syntax.charAt(close++);
                if (current == '(') depth++;
                else if (current == ')') depth--;
            }
            if (depth != 0) { scan = matcher.end(); continue; }
            int afterCall = close;
            while (afterCall < syntax.length() && Character.isWhitespace(syntax.charAt(afterCall))) afterCall++;
            if (afterCall >= syntax.length() || syntax.charAt(afterCall++) != ';') { scan = matcher.end(); continue; }
            while (afterCall < syntax.length() && Character.isWhitespace(syntax.charAt(afterCall))) afterCall++;
            if (!syntax.startsWith("return", afterCall)) { scan = matcher.end(); continue; }
            afterCall += "return".length();
            while (afterCall < syntax.length() && Character.isWhitespace(syntax.charAt(afterCall))) afterCall++;
            if (afterCall >= syntax.length() || syntax.charAt(afterCall) != ';') { scan = matcher.end(); continue; }
            afterCall++;
            normalized.append(body, copied, matcher.start()).append("return ")
                    .append(body, argumentStart, close - 1).append(';');
            copied = afterCall;
            scan = afterCall;
        }
        return copied == 0 ? body : normalized.append(body, copied, body.length()).toString();
    }

    private String normalizeBareReturns(String body) {
        Matcher matcher = Pattern.compile("\\breturn\\s*;").matcher(maskCommentsAndLiterals(body));
        StringBuilder normalized = new StringBuilder();
        int copied = 0;
        while (matcher.find()) {
            normalized.append(body, copied, matcher.start()).append("return 0;");
            copied = matcher.end();
        }
        return copied == 0 ? body : normalized.append(body, copied, body.length()).toString();
    }

    private boolean isTrivia(String text) {
        int i = 0;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) { i++; continue; }
            if (i + 1 < text.length() && text.charAt(i) == '/' && text.charAt(i + 1) == '/') {
                i += 2;
                while (i < text.length() && text.charAt(i) != '\n') i++;
                continue;
            }
            if (i + 1 < text.length() && text.charAt(i) == '/' && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                if (close < 0) return false;
                i = close + 2;
                continue;
            }
            return false;
        }
        return true;
    }
    private void validateStaticMethods(String source, int start, int end) {
        int depth = 1, memberStart = start;
        char quote = 0;
        boolean lineComment = false, blockComment = false, escaped = false;
        for (int i = start; i < end; i++) {
            char c = source.charAt(i), next = i + 1 < end ? source.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (quote != 0) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0; continue; }
            if (c == '/' && next == '/') { lineComment = true; i++; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '"' || c == '\'') { quote = c; continue; }
            if (depth == 1 && c == '(') {
                int nameEnd = i;
                while (nameEnd > memberStart && Character.isWhitespace(source.charAt(nameEnd - 1))) nameEnd--;
                int nameStart = nameEnd;
                while (nameStart > memberStart && Character.isJavaIdentifierPart(source.charAt(nameStart - 1))) nameStart--;
                if (nameStart < nameEnd) {
                    String signature = source.substring(memberStart, nameStart);
                    if (signature.isBlank() || signature.matches("\\s*public\\s*")) {
                        throw new ParseFailure("CB-JAVA-CONSTRUCTOR-UNSUPPORTED",
                                "Java constructors are unsupported because object initialization is not represented in the shared IR.",
                                location(source, nameStart));
                    }
                    if (!signature.isBlank() && !Pattern.compile("\\bstatic\\b").matcher(signature).find()) {
                        throw new ParseFailure("Only static Java methods are supported; instance method '" + source.substring(nameStart, nameEnd) + "' cannot be flattened safely.", location(source, nameStart));
                    }
                }
            }
            if (depth == 1 && c == '{') {
                String member = source.substring(memberStart, i).trim();
                if (!member.contains(")")) {
                    boolean nestedClass = Pattern.compile("\\bclass\\b").matcher(member).find();
                    throw new ParseFailure(nestedClass ? "CB-JAVA-NESTED-CLASS-UNSUPPORTED" : "CB-JAVA-CLASS-STATE-UNSUPPORTED",
                            nestedClass ? "Nested Java classes are outside the supported single-class subset."
                                    : "Class initializer blocks and nested state are outside the supported static-method subset.",
                            location(source, memberStart));
                }
                depth++;
            } else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 1) memberStart = i + 1; }
            else if (depth == 1 && c == ';') {
                String member = source.substring(memberStart, i).trim();
                if (!member.isEmpty() && (!member.contains("(") || member.contains("="))) {
                    boolean arrayField = member.contains("[");
                    throw new ParseFailure(arrayField ? "CB-PARSE-JAVA-UNSUPPORTED" : "CB-JAVA-CLASS-STATE-UNSUPPORTED",
                            arrayField ? "Java array fields are outside the supported subset."
                                    : "Java fields and class state are unsupported; only static methods can be converted safely.",
                            location(source, memberStart));
                }
                memberStart = i + 1;
            }
        }
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

    private void stripFinalMethodModifiers(char[] text, int start, int end) {
        String source = new String(text);
        String syntax = maskCommentsAndLiterals(source);
        Matcher matcher = Pattern.compile("\\bfinal\\s+(?:byte|short|int|long|float|double|char|boolean|String)\\s+[A-Za-z_$][\\w$]*\\s*\\(").matcher(syntax);
        while (matcher.find()) {
            int finalEnd = matcher.start() + "final".length();
            for (int i = matcher.start(); i < finalEnd; i++) if (text[i] != '\n' && text[i] != '\r') text[i] = ' ';
        }
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

    private record UnsupportedJavaSyntax(Pattern pattern, String code, String message) { }
    private static final class ParseFailure extends RuntimeException {
        private final String code;
        private final SourceSpan location;
        private ParseFailure(String message, SourceSpan location) { this("CB-PARSE-JAVA-UNSUPPORTED", message, location); }
        private ParseFailure(String code, String message, SourceSpan location) {
            super(message);
            this.code = code;
            this.location = location;
        }
    }
}
