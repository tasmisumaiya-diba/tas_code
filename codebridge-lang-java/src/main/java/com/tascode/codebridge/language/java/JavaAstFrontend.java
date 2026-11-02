package com.tascode.codebridge.language.java;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.ir.TypeParameter;
import com.tascode.codebridge.ir.TypeReference;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.lang.model.element.Element;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.type.TypeMirror;

/** Java 21 grammar frontend backed by the JDK compiler's public tree API. */
public final class JavaAstFrontend {
    public static final int LANGUAGE_LEVEL = 21;
    private static final int IR_VERSION = 1;

    public Result parse(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return new Result(Optional.empty(), List.of(Diagnostic.withoutLocation(Severity.ERROR,
                "CB-JAVA-FRONTEND-UNAVAILABLE", "Java parsing requires a full JDK with the jdk.compiler module.")));

        DiagnosticCollector<JavaFileObject> javacDiagnostics = new DiagnosticCollector<>();
        String inputSource = source == null ? "" : source;
        JavaFileObject input = new StringJavaFileObject(inputSource);
        try {
            JavacTask task = (JavacTask) compiler.getTask(null, null, javacDiagnostics,
                    List.of("-proc:none", "--release", Integer.toString(LANGUAGE_LEVEL)), null, List.of(input));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            List<Diagnostic> diagnostics = mapDiagnostics(javacDiagnostics, true);
            if (!diagnostics.isEmpty()) return new Result(Optional.empty(), diagnostics);
            if (units.size() != 1) return new Result(Optional.empty(), List.of(Diagnostic.withoutLocation(
                    Severity.ERROR, "CB-JAVA-FRONTEND-UNIT-COUNT", "The Java frontend expected exactly one compilation unit.")));
            task.analyze();
            diagnostics = mapDiagnostics(javacDiagnostics, false);
            CompilationUnitTree unit = units.getFirst();
            Trees trees = Trees.instance(task);
            return new Result(Optional.of(index(unit, trees, inputSource)), diagnostics);
        } catch (IOException | RuntimeException failure) {
            return new Result(Optional.empty(), List.of(Diagnostic.withoutLocation(Severity.ERROR,
                    "CB-JAVA-FRONTEND-FAILED", "The Java syntax frontend could not parse this source: " + failure.getMessage())));
        }
    }

    private List<Diagnostic> mapDiagnostics(DiagnosticCollector<JavaFileObject> collector, boolean syntax) {
        List<Diagnostic> result = new ArrayList<>();
        collector.getDiagnostics().stream().filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR).forEach(d -> {
            String code = syntax ? "CB-JAVA-SYNTAX" : semanticDiagnosticCode(d.getCode());
            String stage = syntax ? "syntax" : "semantic";
            result.add(Diagnostic.at(Severity.ERROR, code,
                    "Java " + LANGUAGE_LEVEL + " " + stage + " error: " + d.getMessage(java.util.Locale.ROOT),
                    SourceSpan.point(Math.max(1, (int) d.getLineNumber()), Math.max(1, (int) d.getColumnNumber()))));
        });
        return List.copyOf(result);
    }

    private String semanticDiagnosticCode(String javacCode) {
        if (javacCode == null) return "CB-JAVA-SEMANTIC";
        if (javacCode.contains("cant.resolve") || javacCode.contains("doesnt.exist") || javacCode.contains("not.def.access"))
            return "CB-JAVA-SYMBOL-UNRESOLVED";
        if (javacCode.contains("incompatible.types") || javacCode.contains("prob.found.req")
                || javacCode.contains("operator.cant.be.applied") || javacCode.contains("cant.apply.symbol")
                || javacCode.contains("ref.ambiguous") || javacCode.contains("bad.operand"))
            return "CB-JAVA-TYPE-ERROR";
        return "CB-JAVA-SEMANTIC";
    }

    private JavaFrontendModel index(CompilationUnitTree unit, Trees trees, String source) {
        SourcePositions positions = trees.getSourcePositions();
        List<String> imports = unit.getImports().stream().map(this::importName).toList();
        List<JavaFrontendModel.TypeModel> types = unit.getTypeDecls().stream()
                .filter(ClassTree.class::isInstance).map(ClassTree.class::cast)
                .map(type -> type(type, unit, positions, source)).toList();
        Set<String> kinds = new LinkedHashSet<>();
        new TreeScanner<Void, Void>() {
            @Override public Void scan(Tree tree, Void unused) {
                if (tree != null) kinds.add(tree.getKind().name());
                return super.scan(tree, unused);
            }
        }.scan(unit, null);
        List<JavaFrontendModel.ResolvedSymbol> symbols = new ArrayList<>();
        List<JavaFrontendModel.AttributedType> attributedTypes = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            @Override public Void scan(Tree tree, Void unused) {
                if (tree != null) {
                    com.sun.source.util.TreePath path = getCurrentPath() == null
                            ? new com.sun.source.util.TreePath(unit) : new com.sun.source.util.TreePath(getCurrentPath(), tree);
                    Element element = trees.getElement(path);
                    if (element != null) {
                        TypeMirror mirror = trees.getTypeMirror(path);
                        String type = mirror == null ? "" : mirror.toString();
                        symbols.add(new JavaFrontendModel.ResolvedSymbol(element.getKind().name(), element.getSimpleName().toString(),
                                qualifiedName(element), type, span(tree, unit, positions, source)));
                    }
                    TypeMirror mirror = trees.getTypeMirror(path);
                    if (mirror != null && !mirror.getKind().name().equals("NONE"))
                        attributedTypes.add(new JavaFrontendModel.AttributedType(tree.getKind().name(), mirror.toString(), span(tree, unit, positions, source)));
                }
                return super.scan(tree, unused);
            }
        }.scan(unit, null);
        return new JavaFrontendModel(LANGUAGE_LEVEL,
                Optional.ofNullable(unit.getPackageName()).map(Object::toString), imports, types, kinds, symbols, attributedTypes);
    }

    private String qualifiedName(Element element) {
        if (element instanceof QualifiedNameable named && !named.getQualifiedName().isEmpty()) return named.getQualifiedName().toString();
        Element owner = element.getEnclosingElement();
        while (owner != null && !(owner instanceof QualifiedNameable)) owner = owner.getEnclosingElement();
        if (owner instanceof QualifiedNameable named && !named.getQualifiedName().isEmpty())
            return named.getQualifiedName() + "." + element.getSimpleName();
        return element.getSimpleName().toString();
    }

    private JavaFrontendModel.TypeModel type(ClassTree type, CompilationUnitTree unit, SourcePositions positions, String source) {
        List<JavaFrontendModel.MemberModel> members = new ArrayList<>();
        List<JavaFrontendModel.TypeModel> nested = new ArrayList<>();
        for (Tree member : type.getMembers()) {
            // javac may insert an implicit default constructor during attribution.
            if (member instanceof MethodTree method && method.getName().contentEquals("<init>")) {
                long start = positions.getStartPosition(unit, member), end = positions.getEndPosition(unit, member);
                String text = start < 0 || end < 0 ? "" : source.substring((int) start, (int) Math.min(end, source.length()));
                if (!Pattern.compile("\\b" + Pattern.quote(type.getSimpleName().toString()) + "\\s*\\(").matcher(text).find()) continue;
            }
            if (member instanceof MethodTree method) members.add(method(method, unit, positions, source));
            else if (member instanceof com.sun.source.tree.VariableTree field) members.add(new JavaFrontendModel.MemberModel(
                    "FIELD", field.getName().toString(), Optional.ofNullable(field.getType()).map(t -> typeReference(t, unit, positions, source)),
                    names(field.getModifiers().getFlags()), List.of(), List.of(), List.of(), false, span(field, unit, positions, source), Optional.ofNullable(field.getInitializer()).map(Object::toString)));
            else if (member instanceof ClassTree nestedType) nested.add(type(nestedType, unit, positions, source));
            else members.add(new JavaFrontendModel.MemberModel(member.getKind().name(), "", Optional.empty(), Set.of(), List.of(), List.of(), List.of(), false, span(member, unit, positions, source), Optional.empty()));
        }
        String kind = switch (type.getKind()) {
            case CLASS -> "CLASS";
            case INTERFACE -> "INTERFACE";
            case ENUM -> "ENUM";
            case ANNOTATION_TYPE -> "ANNOTATION";
            case RECORD -> "RECORD";
            default -> type.getKind().name();
        };
        return new JavaFrontendModel.TypeModel(kind, type.getSimpleName().toString(), names(type.getModifiers().getFlags()),
                type.getTypeParameters().stream().map(p -> typeParameter(p, unit, positions, source)).toList(),
                Optional.ofNullable(type.getExtendsClause()).map(t -> typeReference(t, unit, positions, source)),
                type.getImplementsClause().stream().map(t -> typeReference(t, unit, positions, source)).toList(), members, nested);
    }

    private JavaFrontendModel.MemberModel method(MethodTree method, CompilationUnitTree unit, SourcePositions positions, String source) {
        String name = method.getName().contentEquals("<init>") ? "<init>" : method.getName().toString();
        return new JavaFrontendModel.MemberModel("METHOD", name,
                Optional.ofNullable(method.getReturnType()).map(t -> typeReference(t, unit, positions, source)),
                names(method.getModifiers().getFlags()),
                method.getTypeParameters().stream().map(p -> typeParameter(p, unit, positions, source)).toList(),
                method.getParameters().stream().map(p -> typeReference(p.getType(), unit, positions, source)).toList(),
                method.getThrows().stream().map(t -> typeReference(t, unit, positions, source)).toList(), method.getBody() != null, span(method, unit, positions, source), Optional.empty());
    }

    private TypeParameter typeParameter(TypeParameterTree parameter, CompilationUnitTree unit, SourcePositions positions, String source) {
        return new TypeParameter(IR_VERSION, span(parameter, unit, positions, source), parameter.getName().toString(),
                parameter.getBounds().stream().map(t -> typeReference(t, unit, positions, source)).toList());
    }

    private TypeReference typeReference(Tree tree, CompilationUnitTree unit, SourcePositions positions, String source) {
        SourceSpan span = span(tree, unit, positions, source);
        if (tree instanceof AnnotatedTypeTree annotated) return typeReference(annotated.getUnderlyingType(), unit, positions, source);
        if (tree instanceof ArrayTypeTree array) {
            TypeReference component = typeReference(array.getType(), unit, positions, source);
            return new TypeReference(IR_VERSION, span, component.name(), component.typeArguments(),
                    component.arrayDimensions() + 1, component.boundKind(), component.bound());
        }
        if (tree instanceof ParameterizedTypeTree parameterized) {
            TypeReference raw = typeReference(parameterized.getType(), unit, positions, source);
            return new TypeReference(IR_VERSION, span, raw.name(),
                    parameterized.getTypeArguments().stream().map(t -> typeReference(t, unit, positions, source)).toList(),
                    raw.arrayDimensions(), raw.boundKind(), raw.bound());
        }
        if (tree instanceof WildcardTree wildcard) {
            TypeReference.BoundKind kind = wildcard.getKind() == Tree.Kind.EXTENDS_WILDCARD
                    ? TypeReference.BoundKind.EXTENDS
                    : wildcard.getKind() == Tree.Kind.SUPER_WILDCARD ? TypeReference.BoundKind.SUPER : TypeReference.BoundKind.NONE;
            return new TypeReference(IR_VERSION, span, "?", List.of(), 0, kind,
                    Optional.ofNullable(wildcard.getBound()).map(bound -> typeReference(bound, unit, positions, source)));
        }
        return new TypeReference(IR_VERSION, span, tree.toString());
    }

    private SourceSpan span(Tree tree, CompilationUnitTree unit, SourcePositions positions, String source) {
        long start = positions.getStartPosition(unit, tree);
        long end = positions.getEndPosition(unit, tree);
        if (start < 0 || end < 0 || unit.getLineMap() == null) return SourceSpan.point(1, 1);
        long finalPosition = Math.max(start, Math.min(source.length(), end) - 1);
        return new SourceSpan((int) unit.getLineMap().getLineNumber(start), (int) unit.getLineMap().getColumnNumber(start),
                (int) unit.getLineMap().getLineNumber(finalPosition), (int) unit.getLineMap().getColumnNumber(finalPosition));
    }

    private Set<String> names(Set<? extends javax.lang.model.element.Modifier> modifiers) {
        Set<String> names = new LinkedHashSet<>();
        modifiers.forEach(modifier -> names.add(modifier.name().toLowerCase(java.util.Locale.ROOT)));
        return names;
    }

    private String importName(ImportTree tree) {
        return (tree.isStatic() ? "static " : "") + tree.getQualifiedIdentifier();
    }

    public record Result(Optional<JavaFrontendModel> model, List<Diagnostic> diagnostics) {
        public Result { model = model == null ? Optional.empty() : model; diagnostics = List.copyOf(diagnostics); }
        public boolean successful() { return model.isPresent() && diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR); }
    }

    private static final class StringJavaFileObject extends SimpleJavaFileObject {
        private final String source;
        private StringJavaFileObject(String source) {
            super(sourceUri(source), JavaFileObject.Kind.SOURCE);
            this.source = source;
        }
        private static URI sourceUri(String source) {
            String masked = maskCommentsAndLiterals(source);
            Matcher type = Pattern.compile("\\bpublic\\s+(?:(?:abstract|final|sealed|non-sealed|strictfp)\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)")
                    .matcher(masked);
            String name;
            if (type.find()) name = type.group(1);
            else {
                type = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)").matcher(masked);
                name = type.find() ? type.group(1) : "CodeBridgeInput";
            }
            Matcher packageName = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;").matcher(masked);
            String path = packageName.find() ? packageName.group(1).replace('.', '/') + "/" + name + ".java" : name + ".java";
            return URI.create("string:///" + path);
        }
        private static String maskCommentsAndLiterals(String source) {
            char[] text = source.toCharArray();
            boolean line = false, block = false, escaped = false, textBlock = false;
            char quote = 0;
            for (int i = 0; i < text.length; i++) {
                char c = text[i], next = i + 1 < text.length ? text[i + 1] : 0;
                if (line) { if (c == '\n') line = false; else text[i] = ' '; continue; }
                if (block) { if (c == '*' && next == '/') { text[i] = text[i + 1] = ' '; i++; block = false; } else if (c != '\n' && c != '\r') text[i] = ' '; continue; }
                if (textBlock) {
                    if (c == '"' && i + 2 < text.length && text[i + 1] == '"' && text[i + 2] == '"') { text[i] = text[i + 1] = text[i + 2] = ' '; i += 2; textBlock = false; }
                    else if (c != '\n' && c != '\r') text[i] = ' ';
                    continue;
                }
                if (quote != 0) { if (c != '\n' && c != '\r') text[i] = ' '; if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == quote) quote = 0; continue; }
                if (c == '/' && next == '/') { text[i] = text[i + 1] = ' '; i++; line = true; continue; }
                if (c == '/' && next == '*') { text[i] = text[i + 1] = ' '; i++; block = true; continue; }
                if (c == '"' && i + 2 < text.length && text[i + 1] == '"' && text[i + 2] == '"') { text[i] = text[i + 1] = text[i + 2] = ' '; i += 2; textBlock = true; continue; }
                if (c == '"' || c == '\'') { quote = c; text[i] = ' '; }
            }
            return new String(text);
        }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
    }
}
