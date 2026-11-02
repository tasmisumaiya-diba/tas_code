package com.tascode.codebridge.analysis;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Rejects C identifiers that would become reserved tokens in generated C++. */
public final class CppIdentifierAnalyzer {
    private static final Set<String> KEYWORDS = Set.of(
            "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "bool",
            "break", "case", "catch", "char", "char8_t", "char16_t", "char32_t", "class",
            "compl", "concept", "const", "consteval", "constexpr", "constinit", "const_cast",
            "continue", "co_await", "co_return", "co_yield", "decltype", "default", "delete",
            "do", "double", "dynamic_cast", "else", "enum", "explicit", "export", "extern",
            "false", "float", "for", "friend", "goto", "if", "inline", "int", "long",
            "mutable", "namespace", "new", "noexcept", "not", "not_eq", "nullptr", "operator",
            "or", "or_eq", "private", "protected", "public", "register", "reinterpret_cast",
            "requires", "return", "short", "signed", "sizeof", "static", "static_assert",
            "static_cast", "struct", "switch", "template", "this", "thread_local", "throw",
            "true", "try", "typedef", "typeid", "typename", "union", "unsigned", "using",
            "virtual", "void", "volatile", "wchar_t", "while", "xor", "xor_eq");

    public List<Diagnostic> analyze(Program program, Language source, Language target) {
        if (target != Language.CPP || source != Language.C && source != Language.JAVA) return List.of();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (GlobalVariableDeclaration global : program.globals()) check(global.name(), global.sourceSpan(), diagnostics);
        for (EnumDeclaration enumeration : program.enums()) {
            if (!enumeration.name().isEmpty()) check(enumeration.name(), enumeration.sourceSpan(), diagnostics);
            enumeration.enumerators().forEach(value -> check(value.name(), value.sourceSpan(), diagnostics));
        }
        for (ClassDeclaration type : program.classes()) {
            check(type.name(), type.sourceSpan(), diagnostics);
            type.fields().forEach(field -> check(field.name(), field.sourceSpan(), diagnostics));
            type.methods().forEach(method -> check(method.name(), method.sourceSpan(), diagnostics));
        }
        for (FunctionDeclaration function : program.functions()) {
            check(function.name(), function.sourceSpan(), diagnostics);
            for (Parameter parameter : function.parameters()) check(parameter.name(), parameter.sourceSpan(), diagnostics);
            visit(function.body(), diagnostics);
        }
        for (EnumDeclaration enumeration : program.enums()) {
            if (!enumeration.name().isEmpty()) check(enumeration.name(), enumeration.sourceSpan(), diagnostics);
            for (EnumDeclaration.Enumerator enumerator : enumeration.enumerators()) check(enumerator.name(), enumerator.sourceSpan(), diagnostics);
        }
        return List.copyOf(diagnostics);
    }

    private void visit(Statement statement, List<Diagnostic> diagnostics) {
        if (statement instanceof VariableDeclaration variable) check(variable.name(), variable.sourceSpan(), diagnostics);
        else if (statement instanceof ArrayDeclaration array) check(array.name(), array.sourceSpan(), diagnostics);
        else if (statement instanceof BlockStatement block) block.statements().forEach(child -> visit(child, diagnostics));
        else if (statement instanceof IfStatement conditional) {
            visit(conditional.thenBranch(), diagnostics);
            conditional.elseBranch().ifPresent(branch -> visit(branch, diagnostics));
        } else if (statement instanceof WhileStatement loop) visit(loop.body(), diagnostics);
        else if (statement instanceof DoWhileStatement loop) visit(loop.body(), diagnostics);
        else if (statement instanceof ForStatement loop) {
            loop.initializer().ifPresent(initializer -> visit(initializer, diagnostics));
            visit(loop.body(), diagnostics);
        }
    }

    private void check(String name, com.tascode.codebridge.diagnostic.SourceSpan span, List<Diagnostic> diagnostics) {
        if (KEYWORDS.contains(name)) diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-CPP-IDENTIFIER-RESERVED",
                "C identifier '" + name + "' is a reserved C++ keyword and cannot be emitted as a C++ identifier.", span));
    }
}
