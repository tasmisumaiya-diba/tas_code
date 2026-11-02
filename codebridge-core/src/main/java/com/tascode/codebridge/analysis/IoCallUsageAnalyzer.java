package com.tascode.codebridge.analysis;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Rejects mapped console calls when their language-specific return values are observed. */
public final class IoCallUsageAnalyzer {
    public List<Diagnostic> analyze(Program program, Language from, Language to) {
        if (from == to || from != Language.JAVA && to != Language.JAVA) return List.of();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (FunctionDeclaration function : program.functions()) visitStatement(function.body(), from, diagnostics);
        return List.copyOf(diagnostics);
    }

    private void visitStatement(Statement statement, Language from, List<Diagnostic> diagnostics) {
        if (statement instanceof BlockStatement block) block.statements().forEach(child -> visitStatement(child, from, diagnostics));
        else if (statement instanceof VariableDeclaration variable) variable.initializer().ifPresent(value -> visitExpression(value, false, from, diagnostics));
        else if (statement instanceof ArrayDeclaration array) visitExpression(array.size(), false, from, diagnostics);
        else if (statement instanceof ReturnStatement returned) returned.value().ifPresent(value -> visitExpression(value, false, from, diagnostics));
        else if (statement instanceof ExpressionStatement expression) visitExpression(expression.expression(), true, from, diagnostics);
        else if (statement instanceof IfStatement conditional) {
            visitExpression(conditional.condition(), false, from, diagnostics);
            visitStatement(conditional.thenBranch(), from, diagnostics);
            conditional.elseBranch().ifPresent(branch -> visitStatement(branch, from, diagnostics));
        } else if (statement instanceof WhileStatement loop) {
            visitExpression(loop.condition(), false, from, diagnostics); visitStatement(loop.body(), from, diagnostics);
        } else if (statement instanceof DoWhileStatement loop) {
            visitStatement(loop.body(), from, diagnostics); visitExpression(loop.condition(), false, from, diagnostics);
        } else if (statement instanceof ForStatement loop) {
            loop.initializer().ifPresent(initializer -> visitStatement(initializer, from, diagnostics));
            loop.condition().ifPresent(condition -> visitExpression(condition, false, from, diagnostics));
            loop.update().ifPresent(update -> visitExpression(update, false, from, diagnostics));
            visitStatement(loop.body(), from, diagnostics);
        }
    }

    private void visitExpression(Expression expression, boolean discarded, Language from, List<Diagnostic> diagnostics) {
        if (expression instanceof CallExpression call) {
            if (isMappedIoCall(call.callee(), from) && !discarded) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-IO-RETURN-UNSUPPORTED",
                        "The return value of '" + call.callee() + "' differs across these languages; use it as a standalone statement only.",
                        call.sourceSpan()));
            }
            call.arguments().forEach(argument -> visitExpression(argument, false, from, diagnostics));
        } else if (expression instanceof BinaryExpression binary) {
            visitExpression(binary.left(), false, from, diagnostics); visitExpression(binary.right(), false, from, diagnostics);
        } else if (expression instanceof UnaryExpression unary) visitExpression(unary.operand(), false, from, diagnostics);
        else if (expression instanceof AssignmentExpression assignment) {
            visitExpression(assignment.target(), false, from, diagnostics); visitExpression(assignment.value(), false, from, diagnostics);
        } else if (expression instanceof ArrayAccessExpression access) {
            visitExpression(access.array(), false, from, diagnostics); visitExpression(access.index(), false, from, diagnostics);
        }
    }

    private boolean isMappedIoCall(String callee, Language from) {
        if (from == Language.JAVA) return Set.of("System.out.printf", "System.out.println", "System.out.print").contains(callee);
        return Set.of("printf", "puts", "scanf").contains(callee) || from == Language.CPP && callee.equals("__codebridge_cout");
    }
}