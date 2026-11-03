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
            new UnsupportedJavaSyntax(Pattern.compile("(?m)^\\s*(?:package|import)\\b"), "CB-JAVA-IMPORT-UNSUPPORTED", "Java packages and imports are outside the supported single-class subset, except java.util.Scanner for basic console input."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bfor\\s*\\([^;()]+:\\s*[^)]+\\)"), "CB-JAVA-ENHANCED-FOR-UNSUPPORTED", "Java enhanced-for loops require iterable and iterator semantics that are not represented in the current IR."),
            new UnsupportedJavaSyntax(Pattern.compile("\\(\\s*(?:byte|short|int|long|float|double|char|boolean|String)\\s*\\)\\s*(?=[\\w(])"), "CB-JAVA-CAST-UNSUPPORTED", "Java casts are outside the supported subset because narrowing and reference-cast behavior cannot be preserved generally."),
            new UnsupportedJavaSyntax(Pattern.compile("\\b(?:this|super)\\b"), "CB-JAVA-INSTANCE-REFERENCE-UNSUPPORTED", "Java this/super references require object state and dispatch semantics that the function IR does not represent."),
            new UnsupportedJavaSyntax(Pattern.compile("->"), "CB-JAVA-LAMBDA-UNSUPPORTED", "Java lambda expressions cannot be represented in the current function IR."),
            new UnsupportedJavaSyntax(Pattern.compile("::"), "CB-JAVA-METHOD-REFERENCE-UNSUPPORTED", "Java method references cannot be represented in the current function IR."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bnew\\s+(?!byte\\b|short\\b|int\\b|long\\b|float\\b|double\\b|char\\b|boolean\\b|String\\b|java\\.lang\\.String\\b)[A-Za-z_$]"), "CB-JAVA-OBJECT-CREATION-UNSUPPORTED", "Java object and array creation are outside the supported subset; use supported fixed-size array declarations."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bchar\\s*\\[\\s*\\]"), "CB-JAVA-ARRAY-ELEMENT-UNSUPPORTED", "Java char arrays use UTF-16 elements and cannot be represented by the current C++ char-array mapping."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bnull\\b"), "CB-JAVA-NULL-UNSUPPORTED", "Java null references have no supported C++ representation in this subset."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bvar\\b"), "CB-JAVA-VAR-UNSUPPORTED", "Java local-variable type inference is outside the supported subset; declare the variable type explicitly."),
            new UnsupportedJavaSyntax(Pattern.compile(">>>"), "CB-JAVA-UNSIGNED-SHIFT-UNSUPPORTED", "Java's unsigned right shift (>>>) requires explicit unsigned bit-width conversions that are not represented in the current C++ IR."),
            new UnsupportedJavaSyntax(Pattern.compile("\\bswitch\\s*\\("), "CB-JAVA-SWITCH-UNSUPPORTED", "Java switch statements and expressions are outside the current IR subset."),
            new UnsupportedJavaSyntax(Pattern.compile("@[A-Za-z_$][\\w$]*"), "CB-JAVA-ANNOTATION-UNSUPPORTED", "Java annotations are not preserved by the current C++ generator."),
            new UnsupportedJavaSyntax(Pattern.compile("\\b(?:record|sealed|permits|native)\\b"), "CB-JAVA-MODERN-TYPE-UNSUPPORTED", "Java records, sealed declarations, and native methods are outside the supported subset."),
            new UnsupportedJavaSyntax(Pattern.compile("\\[\\s*\\]\\s*\\[\\s*\\]"), "CB-JAVA-MULTIDIMENSIONAL-ARRAY-UNSUPPORTED", "Only one-dimensional Java arrays are represented in the shared IR."));
    private static final List<String> MODIFIERS = List.of("public", "private", "protected", "static", "strictfp");
    private final SubsetFunctionParser functionParser = new SubsetFunctionParser(Language.JAVA);
    private final JavaAstFrontend frontend = new JavaAstFrontend();

    @Override public Language language() { return Language.JAVA; }

    @Override public ParseResult parse(String source) {
        if (source == null || source.isBlank()) {
            return new ParseResult(null, List.of(Diagnostic.withoutLocation(Severity.ERROR, "CB-PARSE-EMPTY", "Source code is empty.")));
        }
        String originalSource = source;
        JavaAstFrontend.Result frontendResult = frontend.parse(source);
        if (frontendResult.model().isEmpty()) return new ParseResult(null, frontendResult.diagnostics());
        ScannerLowering scannerLowering = lowerScannerInput(source, frontendResult.model().orElseThrow());
        if (!scannerLowering.diagnostics().isEmpty()) return new ParseResult(null, scannerLowering.diagnostics());
        source = scannerLowering.source();
        FieldExtraction fieldExtraction = extractPrimitiveStaticFields(source, frontendResult.model().orElseThrow());
        if (!fieldExtraction.diagnostics().isEmpty()) return new ParseResult(null, fieldExtraction.diagnostics());
        source = fieldExtraction.source();
        String className = frontendResult.model().orElseThrow().types().getFirst().name();
        source = normalizeClassQualifiedStaticAccess(source, className, frontendResult.model().orElseThrow());
        source = normalizeThisQualifiedMembers(source);
        java.util.Set<String> stackObjects = fieldExtraction.classes().isEmpty()
                ? java.util.Set.of() : simpleObjectNames(source, className);
        if (!stackObjects.isEmpty()) source = normalizeSimpleObjectDeclarations(source, className);
        if (!stackObjects.isEmpty()) {
            String objectNames = stackObjects.stream().map(Pattern::quote).collect(java.util.stream.Collectors.joining("|", "(?:", ")"));
            Matcher alias = Pattern.compile("\\b" + Pattern.quote(className) + "\\s+[A-Za-z_$][\\w$]*\\s*=").matcher(maskCommentsAndLiterals(source));
            Matcher reassignment = Pattern.compile("\\b" + objectNames + "\\s*=\\s*(?:" + objectNames + ")\\b").matcher(maskCommentsAndLiterals(source));
            if (alias.find() || reassignment.find()) return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR,
                    "CB-JAVA-OBJECT-ALIAS-UNSUPPORTED", "Java object variables are references; copying or reassigning them would change alias behavior in C++. Use separately constructed local objects.",
                    location(source, alias.find(0) ? alias.start() : reassignment.find(0) ? reassignment.start() : 0))));
        }
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
        for (JavaFrontendModel.MemberModel member : frontendResult.model().orElseThrow().types().getFirst().members()) {
            if (member.kind().equals("METHOD") && !member.name().equals("<init>")
                    && (member.type().map(type -> type.name().equals(className)).orElse(false)
                    || member.parameterTypes().stream().anyMatch(type -> type.name().equals(className)))) {
                return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-OBJECT-REFERENCE-UNSUPPORTED",
                        "Object-valued parameters and returns use Java reference identity; only local no-argument object construction is supported.", member.sourceSpan())));
            }
            if (member.kind().equals("METHOD") && member.modifiers().contains("static")
                    && (member.modifiers().contains("private") || member.modifiers().contains("protected"))) {
                return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-ACCESS-UNSUPPORTED",
                        "Private or protected static methods cannot be flattened into C++ free functions without changing visibility.", member.sourceSpan())));
            }
            if (member.kind().equals("METHOD") && !member.name().equals("<init>")
                    && !member.modifiers().contains("static")
                    && !member.modifiers().contains("public") && !member.modifiers().contains("private")
                    && !member.modifiers().contains("protected")) {
                return new ParseResult(null, List.of(Diagnostic.at(Severity.ERROR, "CB-JAVA-ACCESS-UNSUPPORTED",
                        "Package-private instance methods have no direct C++ access equivalent; declare the method public, protected, or private.", member.sourceSpan())));
            }
        }
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
            ParseResult result = new SubsetFunctionParser(Language.JAVA,
                    java.util.Set.of(frontendResult.model().orElseThrow().types().getFirst().name())).parse(normalized);
            if (result.successful() && result.root() instanceof com.tascode.codebridge.ir.Program program) {
                var lowered = attachInstanceMethods(program, fieldExtraction, originalSource, frontendResult.model().orElseThrow());
                if (!lowered.diagnostics().isEmpty()) return new ParseResult(null, lowered.diagnostics());
                return new ParseResult(lowered.program(), result.diagnostics(), frontendResult.diagnostics());
            }
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

    /** Lowers the supported Scanner(System.in) primitive-token reads after javac validates Java syntax. */
    private ScannerLowering lowerScannerInput(String source, JavaFrontendModel model) {
        if (!model.imports().contains("java.util.Scanner")) return new ScannerLowering(source, List.of());
        Pattern importPattern = Pattern.compile("\\bimport[ \\t]+java\\.util\\.Scanner[ \\t]*;");
        Matcher importMatcher = importPattern.matcher(maskCommentsAndLiterals(source));
        if (!importMatcher.find()) return scannerFailure(source, "CB-JAVA-SCANNER-UNSUPPORTED",
                "Only a normal java.util.Scanner import is supported for console input.", source.indexOf("import"));
        String scannerImportFree = blankSourceRange(source, importMatcher.start(), importMatcher.end());
        Pattern scannerDeclaration = Pattern.compile("\\b(?:final\\s+)?Scanner\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*new\\s+Scanner\\s*\\(\\s*System\\s*\\.\\s*in\\s*\\)\\s*;");
        Matcher scannerMatcher = scannerDeclaration.matcher(maskCommentsAndLiterals(scannerImportFree));
        List<String> scannerNames = new java.util.ArrayList<>();
        List<TextEdit> declarationEdits = new java.util.ArrayList<>();
        while (scannerMatcher.find()) {
            scannerNames.add(scannerMatcher.group(1));
            declarationEdits.add(new TextEdit(scannerMatcher.start(), scannerMatcher.end(),
                    blankSourceRange(scannerImportFree, scannerMatcher.start(), scannerMatcher.end())
                            .substring(scannerMatcher.start(), scannerMatcher.end())));
        }
        if (scannerNames.isEmpty()) {
            if (!Pattern.compile("\\bScanner\\b").matcher(maskCommentsAndLiterals(scannerImportFree)).find())
                return new ScannerLowering(scannerImportFree, List.of());
            return scannerFailure(source, "CB-JAVA-SCANNER-UNSUPPORTED",
                    "Supported Scanner input must be initialized with new Scanner(System.in).", Math.max(0, source.indexOf("Scanner")));
        }
        if (scannerNames.size() > 1) {
            return scannerFailure(source, "CB-JAVA-SCANNER-MULTIPLE-UNSUPPORTED",
                    "Multiple Java Scanner objects can buffer System.in independently, while the C++ mapping uses one shared stream. Use a single Scanner variable for all input reads.",
                    source.indexOf("Scanner"));
        }
        String scannerAlternation = scannerNames.stream().map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|", "(?:", ")"));
        Pattern inputReadPattern = Pattern.compile("\\b(?:" + scannerAlternation.substring(3, scannerAlternation.length() - 1)
                + ")\\s*\\.\\s*(?:nextInt|nextLong|nextFloat|nextDouble|next|nextLine)\\s*\\(");
        Matcher inputReads = inputReadPattern.matcher(maskCommentsAndLiterals(scannerImportFree));
        List<Integer> inputReadOffsets = new java.util.ArrayList<>();
        while (inputReads.find()) inputReadOffsets.add(inputReads.start());
        Pattern closePattern = Pattern.compile("\\b" + scannerAlternation + "\\s*\\.\\s*close\\s*\\(\\s*\\)\\s*;");
        Matcher sourceCloseMatcher = closePattern.matcher(maskCommentsAndLiterals(scannerImportFree));
        while (sourceCloseMatcher.find()) {
            int closeOffset = sourceCloseMatcher.start();
            if (inputReadOffsets.stream().anyMatch(readOffset -> readOffset > closeOffset)) {
                return scannerFailure(source, "CB-JAVA-SCANNER-CLOSED-READ",
                        "Java closes System.in when a Scanner is closed; a later Scanner read is invalid and cannot be mapped to the shared C++ input stream.",
                        closeOffset);
            }
        }
        String rewritten = applyTextEdits(scannerImportFree, declarationEdits);
        Pattern primitiveRead = Pattern.compile("\\b(int|long|float|double|String)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*"
                + scannerAlternation + "\\s*\\.\\s*next(Int|Long|Float|Double|Line|)?\\s*\\(\\s*\\)\\s*;");
        Matcher readMatcher = primitiveRead.matcher(maskCommentsAndLiterals(rewritten));
        List<TextEdit> readEdits = new java.util.ArrayList<>();
        int readCount = 0;
        while (readMatcher.find()) {
            readCount++;
            String type = readMatcher.group(1), name = readMatcher.group(2), method = readMatcher.group(3);
            String methodType = method == null || method.isEmpty() || method.equals("Line") ? "String" : switch (method) { case "Int" -> "int"; case "Long" -> "long"; case "Float" -> "float"; default -> "double"; };
            if (!type.equals(methodType))
                return scannerFailure(source, "CB-JAVA-SCANNER-UNSUPPORTED",
                        "Scanner read methods must match the declared String or numeric local type.", readMatcher.start());
            String markerType = method != null && method.equals("Line") ? "line" : type.equals("String") ? "string" : type;
            readEdits.add(new TextEdit(readMatcher.start(), readMatcher.end(), type + " " + name
                    + "; __codebridge_read_" + markerType + "(" + name + ");"));
        }
        rewritten = applyTextEdits(rewritten, readEdits);
        Matcher closeMatcher = closePattern.matcher(maskCommentsAndLiterals(rewritten));
        List<TextEdit> closeEdits = new java.util.ArrayList<>();
        while (closeMatcher.find()) closeEdits.add(new TextEdit(closeMatcher.start(), closeMatcher.end(),
                blankSourceRange(rewritten, closeMatcher.start(), closeMatcher.end()).substring(closeMatcher.start(), closeMatcher.end())));
        rewritten = applyTextEdits(rewritten, closeEdits);
        String remainingSyntax = maskCommentsAndLiterals(rewritten);
        if (Pattern.compile("\\bScanner\\b|\\b(?:" + scannerAlternation.substring(3, scannerAlternation.length() - 1) + ")\\b").matcher(remainingSyntax).find())
            return scannerFailure(source, "CB-JAVA-SCANNER-UNSUPPORTED",
                    "Supported Scanner methods are matching nextInt/nextLong/nextFloat/nextDouble for primitive locals, next()/nextLine() for String locals, and standalone close(). Other Scanner methods need an explicit mapping.",
                    source.indexOf(scannerNames.getFirst()));
        if (readCount == 0) return scannerFailure(source, "CB-JAVA-SCANNER-UNSUPPORTED",
                "The supported Scanner subset requires at least one supported token or line read.",
                source.indexOf("Scanner"));
        return new ScannerLowering(rewritten, List.of());
    }

    private ScannerLowering scannerFailure(String source, String code, String message, int offset) {
        return new ScannerLowering(source, List.of(Diagnostic.at(Severity.ERROR, code, message,
                location(source, Math.max(0, offset)))));
    }

    /** Lowers the safe stack-object form; the Java compiler frontend has already validated the source. */
    private String normalizeSimpleObjectDeclarations(String source, String className) {
        Pattern declaration = Pattern.compile("\\b" + Pattern.quote(className)
                + "\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*new\\s+" + Pattern.quote(className)
                + "\\s*\\(\\s*\\)\\s*;");
        Matcher matcher = declaration.matcher(maskCommentsAndLiterals(source));
        char[] rewritten = source.toCharArray();
        while (matcher.find()) {
            String replacement = className + " " + matcher.group(1) + ";";
            int offset = matcher.start();
            for (int i = matcher.start(); i < matcher.end(); i++)
                if (rewritten[i] != '\n' && rewritten[i] != '\r') rewritten[i] = ' ';
            for (int i = 0; i < replacement.length(); i++) rewritten[offset + i] = replacement.charAt(i);
        }
        return new String(rewritten);
    }

    /** Rewrites explicit current-instance member access to its equivalent unqualified class member form. */
    private String normalizeThisQualifiedMembers(String source) {
        Matcher matcher = Pattern.compile("\\bthis\\s*\\.\\s*").matcher(maskCommentsAndLiterals(source));
        char[] rewritten = source.toCharArray();
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++)
                if (rewritten[i] != '\n' && rewritten[i] != '\r') rewritten[i] = ' ';
        }
        return new String(rewritten);
    }

    /** Removes the declaring class qualifier only for known static fields and methods. */
    private String normalizeClassQualifiedStaticAccess(String source, String className, JavaFrontendModel model) {
        java.util.Set<String> staticMembers = new java.util.LinkedHashSet<>();
        for (JavaFrontendModel.MemberModel member : model.types().getFirst().members()) {
            if (member.modifiers().contains("static")
                    && (member.kind().equals("FIELD") || member.kind().equals("METHOD"))) {
                staticMembers.add(member.name());
            }
        }
        if (staticMembers.isEmpty()) return source;
        String names = staticMembers.stream().map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining("|", "(?:", ")"));
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(className) + "\\s*\\.\\s*(" + names + ")\\b")
                .matcher(maskCommentsAndLiterals(source));
        char[] rewritten = source.toCharArray();
        while (matcher.find()) {
            int memberStart = matcher.start(1);
            for (int i = matcher.start(); i < memberStart; i++)
                if (rewritten[i] != '\n' && rewritten[i] != '\r') rewritten[i] = ' ';
        }
        return new String(rewritten);
    }

    private java.util.Set<String> simpleObjectNames(String source, String className) {
        Pattern declaration = Pattern.compile("\\b" + Pattern.quote(className)
                + "\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*new\\s+" + Pattern.quote(className)
                + "\\s*\\(\\s*\\)\\s*;");
        Matcher matcher = declaration.matcher(maskCommentsAndLiterals(source));
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        while (matcher.find()) names.add(matcher.group(1));
        return java.util.Set.copyOf(names);
    }

    private String blankSourceRange(String source, int start, int end) {
        char[] rewritten = source.toCharArray();
        for (int i = Math.max(0, start); i < Math.min(end, rewritten.length); i++)
            if (rewritten[i] != '\n' && rewritten[i] != '\r') rewritten[i] = ' ';
        return new String(rewritten);
    }

    private String applyTextEdits(String source, List<TextEdit> edits) {
        if (edits.isEmpty()) return source;
        StringBuilder result = new StringBuilder(source.length());
        int cursor = 0;
        for (TextEdit edit : edits.stream().sorted(java.util.Comparator.comparingInt(TextEdit::start)).toList()) {
            result.append(source, cursor, edit.start()).append(edit.replacement());
            cursor = edit.end();
        }
        return result.append(source, cursor, source.length()).toString();
    }

    private record ScannerLowering(String source, List<Diagnostic> diagnostics) { }
    private record TextEdit(int start, int end, String replacement) { }

    private FieldExtraction extractPrimitiveStaticFields(String source, JavaFrontendModel model) {
        if (model.types().isEmpty() || !model.types().getFirst().kind().equals("CLASS"))
            return new FieldExtraction(source, List.of(), List.of(), List.of());
        List<com.tascode.codebridge.ir.GlobalVariableDeclaration> globals = new java.util.ArrayList<>();
        List<com.tascode.codebridge.ir.FieldDeclaration> instanceFields = new java.util.ArrayList<>();
        List<com.tascode.codebridge.ir.ConstructorDeclaration> constructors = new java.util.ArrayList<>();
        List<JavaFrontendModel.MemberModel> instanceMethods = model.types().getFirst().members().stream()
                .filter(member -> member.kind().equals("METHOD") && !member.name().equals("<init>")
                        && !member.modifiers().contains("static"))
                .toList();
        char[] rewritten = source.toCharArray();
        for (JavaFrontendModel.MemberModel member : model.types().getFirst().members()) {
            if (member.kind().equals("METHOD") && member.name().equals("<init>")) {
                if (!member.modifiers().contains("public") && !member.modifiers().contains("private")
                        && !member.modifiers().contains("protected")) return new FieldExtraction(source, List.of(), List.of(), List.of(), List.of(Diagnostic.at(
                        Severity.ERROR, "CB-JAVA-ACCESS-UNSUPPORTED",
                        "Package-private constructors have no direct C++ access equivalent; declare the constructor public, protected, or private.", member.sourceSpan())));
                if (!member.parameterTypes().isEmpty() || !member.hasBody()) return new FieldExtraction(source, List.of(), List.of(), List.of(), List.of(Diagnostic.at(
                        Severity.ERROR, "CB-JAVA-CONSTRUCTOR-UNSUPPORTED",
                        "Only explicit no-argument constructors with an empty body are currently supported.", member.sourceSpan())));
                String constructorSource = sourceSlice(source, member.sourceSpan());
                int openBody = constructorSource.indexOf('{');
                int closeBody = constructorSource.lastIndexOf('}');
                if (openBody < 0 || closeBody <= openBody || !isTrivia(constructorSource.substring(openBody + 1, closeBody)))
                    return new FieldExtraction(source, List.of(), List.of(), List.of(), List.of(Diagnostic.at(
                            Severity.ERROR, "CB-JAVA-CONSTRUCTOR-UNSUPPORTED",
                            "Constructor bodies with initialization logic are not yet supported; keep the constructor body empty.", member.sourceSpan())));
                constructors.add(new com.tascode.codebridge.ir.ConstructorDeclaration(1, member.sourceSpan(), model.types().getFirst().name(),
                        List.of(), List.of(), member.modifiers(), new com.tascode.codebridge.ir.BlockStatement(1, member.sourceSpan(), List.of())));
                int start = offsetAt(source, member.sourceSpan().startLine(), member.sourceSpan().startColumn());
                int end = Math.min(source.length(), offsetAt(source, member.sourceSpan().endLine(), member.sourceSpan().endColumn()) + 1);
                for (int i = start; i < end; i++) if (rewritten[i] != '\n' && rewritten[i] != '\r') rewritten[i] = ' ';
                continue;
            }
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
        if (!instanceFields.isEmpty() || !instanceMethods.isEmpty() || !constructors.isEmpty()) {
            JavaFrontendModel.TypeModel type = model.types().getFirst();
            if (!type.modifiers().contains("public")) return new FieldExtraction(source, List.of(), List.of(), List.of(Diagnostic.at(
                    Severity.ERROR, "CB-JAVA-PACKAGE-ACCESS-UNSUPPORTED",
                    "Package-private data classes have no direct C++ access equivalent; declare the class public.", SourceSpan.point(1, 1))));
            String visibility = type.modifiers().contains("public") ? "public" : type.modifiers().contains("private") ? "private" : "";
            classes = List.of(new com.tascode.codebridge.ir.ClassDeclaration(1, SourceSpan.point(1, 1), type.name(),
                    com.tascode.codebridge.ir.ClassDeclaration.Kind.CLASS, type.modifiers(), visibility, List.of(), Optional.empty(),
                    List.of(), instanceFields, constructors, List.of(), List.of()));
        }
        return new FieldExtraction(new String(rewritten), globals, classes, instanceMethods, List.of());
    }

    private LoweredJavaProgram attachInstanceMethods(com.tascode.codebridge.ir.Program program,
                                                    FieldExtraction extraction, String source, JavaFrontendModel model) {
        if (extraction.instanceMethods().isEmpty()) {
            return new LoweredJavaProgram(new com.tascode.codebridge.ir.Program(program.irVersion(), program.sourceSpan(),
                    program.functions(), program.includes(), extraction.globals(), program.enums(), extraction.classes()), List.of());
        }
        List<com.tascode.codebridge.ir.FunctionDeclaration> freeFunctions = new java.util.ArrayList<>(program.functions());
        List<com.tascode.codebridge.ir.MethodDeclaration> methods = new java.util.ArrayList<>();
        List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        for (JavaFrontendModel.MemberModel member : extraction.instanceMethods()) {
            String memberSource = sourceSlice(source, member.sourceSpan());
            memberSource = normalizeClassQualifiedStaticAccess(memberSource, extraction.classes().getFirst().name(), model);
            memberSource = normalizeThisQualifiedMembers(memberSource);
            String memberSyntax = maskCommentsAndLiterals(memberSource);
            String qualifiedAccessCheck = memberSyntax.replaceAll(
                    "\\bSystem\\s*\\.\\s*out\\s*\\.\\s*(?:print|println|printf)\\s*(?=\\()", " ");
            if (Pattern.compile("\\bString\\b|\\b[A-Za-z_$][\\w$]*\\s*\\.\\s*[A-Za-z_$][\\w$]*")
                    .matcher(qualifiedAccessCheck).find()) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-JAVA-INSTANCE-MEMBER-ACCESS-UNSUPPORTED",
                        "Instance methods support primitive state, same-class calls, and basic System.out output; other Java library calls and qualified member access need explicit mappings.", member.sourceSpan()));
                continue;
            }
            if (!member.hasBody()) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-JAVA-ABSTRACT-METHOD-UNSUPPORTED",
                        "Abstract or bodyless instance methods cannot be emitted as concrete C++ methods.", member.sourceSpan()));
                continue;
            }
            if (member.type().isEmpty() || !supportedInstanceValueType(member.type().orElseThrow())
                    || member.parameterTypes().stream().anyMatch(type -> !supportedInstanceValueType(type))) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-JAVA-INSTANCE-METHOD-TYPE-UNSUPPORTED",
                        "Instance methods currently support primitive return and parameter types only.", member.sourceSpan()));
                continue;
            }
            int match = -1;
            for (int i = 0; i < freeFunctions.size(); i++) {
                var function = freeFunctions.get(i);
                if (matchesJavaSignature(function, member)) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-JAVA-INSTANCE-METHOD-LOWERING",
                        "The instance method body could not be lowered by the supported statement parser.", member.sourceSpan()));
                continue;
            }
            var function = freeFunctions.remove(match);
            methods.add(new com.tascode.codebridge.ir.MethodDeclaration(1, member.sourceSpan(), member.name(),
                    new com.tascode.codebridge.ir.TypeReference(1, member.sourceSpan(), function.returnType()),
                    function.parameters(), List.of(), List.of(), member.modifiers(), Optional.of(function.body())));
        }
        if (!diagnostics.isEmpty()) return new LoweredJavaProgram(program, diagnostics);
        var classDecl = extraction.classes().getFirst();
        var updatedClass = new com.tascode.codebridge.ir.ClassDeclaration(classDecl.irVersion(), classDecl.sourceSpan(),
                classDecl.name(), classDecl.kind(), classDecl.modifiers(), classDecl.visibility(), classDecl.typeParameters(),
                classDecl.superclass(), classDecl.interfaces(), classDecl.fields(), classDecl.constructors(), methods, classDecl.nestedTypes());
        return new LoweredJavaProgram(new com.tascode.codebridge.ir.Program(program.irVersion(), program.sourceSpan(), freeFunctions,
                program.includes(), extraction.globals(), program.enums(), List.of(updatedClass)), List.of());
    }

    private boolean matchesJavaSignature(com.tascode.codebridge.ir.FunctionDeclaration function,
                                         JavaFrontendModel.MemberModel member) {
        if (!function.name().equals(member.name()) || function.parameters().size() != member.parameterTypes().size()) return false;
        for (int i = 0; i < function.parameters().size(); i++) {
            var sourceType = member.parameterTypes().get(i);
            if (sourceType.arrayDimensions() != 0 || !sourceType.typeArguments().isEmpty()) return false;
            String expected = sourceType.name().equals("boolean") ? "bool" : sourceType.name();
            if (!function.parameters().get(i).type().equals(expected)) return false;
        }
        String expectedReturn = member.type().map(type -> type.name().equals("boolean") ? "bool" : type.name()).orElse("");
        return function.returnType().equals(expectedReturn);
    }

    private boolean supportedInstanceValueType(com.tascode.codebridge.ir.TypeReference type) {
        return type.arrayDimensions() == 0 && type.typeArguments().isEmpty()
                && List.of("byte", "short", "int", "long", "float", "double", "char", "boolean", "void", "bool").contains(type.name());
    }

    private String sourceSlice(String source, SourceSpan span) {
        int start = offsetAt(source, span.startLine(), span.startColumn());
        int end = Math.min(source.length(), offsetAt(source, span.endLine(), span.endColumn()) + 1);
        return source.substring(Math.min(start, end), end);
    }

    private record LoweredJavaProgram(com.tascode.codebridge.ir.Program program, List<Diagnostic> diagnostics) { }

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
                                   List<JavaFrontendModel.MemberModel> instanceMethods,
                                   List<Diagnostic> diagnostics) {
        private FieldExtraction(String source, List<com.tascode.codebridge.ir.GlobalVariableDeclaration> globals,
                                List<com.tascode.codebridge.ir.ClassDeclaration> classes, List<Diagnostic> diagnostics) {
            this(source, globals, classes, List.of(), diagnostics);
        }
    }
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
                    if (signature.isBlank() || signature.matches("\\s*public\\s*")) continue;
                    // Instance methods remain in the source long enough to lower their bodies into class IR.
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
