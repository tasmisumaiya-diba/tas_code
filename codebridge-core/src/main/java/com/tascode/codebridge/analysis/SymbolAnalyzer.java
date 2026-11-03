package com.tascode.codebridge.analysis;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Checks the lexical names supported by the current function-only IR subset. */
public final class SymbolAnalyzer {
    public List<Diagnostic> analyze(Program program) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Set<String> functions = new HashSet<>();
        Set<String> translationUnitIdentifiers = new HashSet<>();
        for (EnumDeclaration enumeration : program.enums()) {
            for (EnumDeclaration.Enumerator enumerator : enumeration.enumerators()) {
                if (!translationUnitIdentifiers.add(enumerator.name())) diagnostics.add(Diagnostic.at(Severity.ERROR,
                        "CB-SYMBOL-DUP-GLOBAL", "Enumerator '" + enumerator.name() + "' conflicts with another file-scope name.", enumerator.sourceSpan()));
            }
        }
        for (GlobalVariableDeclaration global : program.globals()) {
            if (!translationUnitIdentifiers.add(global.name())) diagnostics.add(Diagnostic.at(Severity.ERROR,
                    "CB-SYMBOL-DUP-GLOBAL", "Global variable '" + global.name() + "' conflicts with another file-scope name.", global.sourceSpan()));
        }
        for (FunctionDeclaration function : program.functions()) {
            boolean duplicateFunction = !functions.add(function.name());
            if (duplicateFunction) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-SYMBOL-DUP-FUNCTION",
                        "Function '" + function.name() + "' is declared more than once.", function.sourceSpan()));
            }
            if (!duplicateFunction && !translationUnitIdentifiers.add(function.name())) diagnostics.add(Diagnostic.at(Severity.ERROR,
                    "CB-SYMBOL-DUP-GLOBAL", "Function '" + function.name() + "' conflicts with another file-scope name.", function.sourceSpan()));
            Deque<Set<String>> scopes = new ArrayDeque<>();
            Set<String> globals = new HashSet<>(translationUnitIdentifiers);
            scopes.push(globals);
            scopes.push(new HashSet<>());
            for (Parameter parameter : function.parameters()) {
                if (!scopes.peek().add(parameter.name())) diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-SYMBOL-DUP-PARAMETER",
                        "Parameter '" + parameter.name() + "' is declared more than once.", parameter.sourceSpan()));
            }
            analyzeBlock(function.body(), scopes, diagnostics, false);
        }
        return List.copyOf(diagnostics);
    }

    private void analyzeBlock(BlockStatement block, Deque<Set<String>> scopes, List<Diagnostic> diagnostics, boolean nested) {
        if (nested) scopes.push(new HashSet<>());
        for (Statement statement : block.statements()) analyzeStatement(statement, scopes, diagnostics);
        if (nested) scopes.pop();
    }

    private void analyzeStatement(Statement statement, Deque<Set<String>> scopes, List<Diagnostic> diagnostics) {
        if (statement instanceof BlockStatement block) analyzeBlock(block, scopes, diagnostics, true);
        else if (statement instanceof VariableDeclaration variable) {
            declare(variable.name(), variable.sourceSpan(), scopes, diagnostics, "local variable");
            variable.initializer().ifPresent(value -> analyzeExpression(value, scopes, diagnostics));
        } else if (statement instanceof ArrayDeclaration array) {
            declare(array.name(), array.sourceSpan(), scopes, diagnostics, "local variable");
            analyzeExpression(array.size(), scopes, diagnostics);
        } else if (statement instanceof ReturnStatement returned) returned.value().ifPresent(value -> analyzeExpression(value, scopes, diagnostics));
        else if (statement instanceof ExpressionStatement expression) analyzeExpression(expression.expression(), scopes, diagnostics);
        else if (statement instanceof IfStatement conditional) {
            analyzeExpression(conditional.condition(), scopes, diagnostics);
            analyzeStatement(conditional.thenBranch(), scopes, diagnostics);
            conditional.elseBranch().ifPresent(branch -> analyzeStatement(branch, scopes, diagnostics));
        } else if (statement instanceof WhileStatement loop) {
            analyzeExpression(loop.condition(), scopes, diagnostics); analyzeStatement(loop.body(), scopes, diagnostics);
        } else if (statement instanceof DoWhileStatement loop) {
            analyzeStatement(loop.body(), scopes, diagnostics); analyzeExpression(loop.condition(), scopes, diagnostics);
        } else if (statement instanceof ForStatement loop) {
            scopes.push(new HashSet<>());
            loop.initializer().ifPresent(initializer -> { if (initializer instanceof BlockStatement declarations) declarations.statements().forEach(declaration -> analyzeStatement(declaration, scopes, diagnostics)); else analyzeStatement(initializer, scopes, diagnostics); });
            loop.condition().ifPresent(condition -> analyzeExpression(condition, scopes, diagnostics));
            loop.update().ifPresent(update -> analyzeExpression(update, scopes, diagnostics));
            analyzeStatement(loop.body(), scopes, diagnostics);
            scopes.pop();
        }
    }

    private void analyzeExpression(Expression expression, Deque<Set<String>> scopes, List<Diagnostic> diagnostics) {
        if (expression instanceof NameExpression name) {
            String identifier = name.name();
            int member = identifier.indexOf('.');
            boolean objectMember = member > 0 && identifier.indexOf('.', member + 1) < 0
                    && scopes.stream().anyMatch(scope -> scope.contains(identifier.substring(0, member)));
            if (!objectMember && scopes.stream().noneMatch(scope -> scope.contains(identifier))) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-SYMBOL-UNRESOLVED",
                        "Name '" + name.name() + "' is not declared in this function.", name.sourceSpan()));
            }
        } else if (expression instanceof BinaryExpression binary) {
            analyzeExpression(binary.left(), scopes, diagnostics); analyzeExpression(binary.right(), scopes, diagnostics);
        } else if (expression instanceof UnaryExpression unary) analyzeExpression(unary.operand(), scopes, diagnostics);
        else if (expression instanceof AssignmentExpression assignment) {
            analyzeExpression(assignment.target(), scopes, diagnostics); analyzeExpression(assignment.value(), scopes, diagnostics);
        } else if (expression instanceof UpdateExpression update) analyzeExpression(update.target(), scopes, diagnostics);
        else if (expression instanceof ArrayAccessExpression access) {
            analyzeExpression(access.array(), scopes, diagnostics); analyzeExpression(access.index(), scopes, diagnostics);
        } else if (expression instanceof CallExpression call) {
            for (Expression argument : call.arguments()) analyzeExpression(argument, scopes, diagnostics);
        }
    }

    private void declare(String name, com.tascode.codebridge.diagnostic.SourceSpan span, Deque<Set<String>> scopes,
                         List<Diagnostic> diagnostics, String kind) {
        if (!scopes.peek().add(name)) {
            diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-SYMBOL-DUP-LOCAL",
                    "The " + kind + " '" + name + "' is already declared in this scope.", span));
        }
    }
}
