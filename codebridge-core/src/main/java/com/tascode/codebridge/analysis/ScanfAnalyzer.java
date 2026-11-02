package com.tascode.codebridge.analysis;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import java.util.*;

/** Validates supported standalone C scanf and C++ cin reads into local variables. */
public final class ScanfAnalyzer {
    public List<Diagnostic> analyze(Program program, Language source, Language target) {
        if (target != Language.JAVA || source == Language.JAVA) return List.of();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (FunctionDeclaration function : program.functions()) {
            Map<String, String> types = new HashMap<>();
            function.parameters().forEach(parameter -> types.put(parameter.name(), normalize(parameter.type())));
            collectTypes(function.body(), types);
            visitBlock(function.body(), types, diagnostics);
        }
        return List.copyOf(diagnostics);
    }

    private void collectTypes(BlockStatement block, Map<String, String> types) {
        for (Statement statement : block.statements()) {
            if (statement instanceof VariableDeclaration variable) types.put(variable.name(), normalize(variable.type()));
            else if (statement instanceof ArrayDeclaration array) types.put(array.name(), normalize(array.elementType()) + "[]");
            else if (statement instanceof BlockStatement nested) collectTypes(nested, types);
            else if (statement instanceof IfStatement conditional) {
                collectTypes(conditional.thenBranch(), types); conditional.elseBranch().ifPresent(branch -> collectTypes(branch, types));
            } else if (statement instanceof WhileStatement loop) collectTypes(loop.body(), types);
            else if (statement instanceof DoWhileStatement loop) collectTypes(loop.body(), types);
            else if (statement instanceof ForStatement loop) {
                loop.initializer().ifPresent(initializer -> collectTypes(initializer, types)); collectTypes(loop.body(), types);
            }
        }
    }

    private void collectTypes(Statement statement, Map<String, String> types) {
        if (statement instanceof BlockStatement block) collectTypes(block, types);
        else if (statement instanceof VariableDeclaration variable) types.put(variable.name(), normalize(variable.type()));
        else if (statement instanceof ArrayDeclaration array) types.put(array.name(), normalize(array.elementType()) + "[]");
        else if (statement instanceof IfStatement conditional) {
            collectTypes(conditional.thenBranch(), types); conditional.elseBranch().ifPresent(branch -> collectTypes(branch, types));
        } else if (statement instanceof WhileStatement loop) collectTypes(loop.body(), types);
        else if (statement instanceof DoWhileStatement loop) collectTypes(loop.body(), types);
    }

    private void visitBlock(BlockStatement block, Map<String, String> types, List<Diagnostic> diagnostics) {
        for (Statement statement : block.statements()) visitStatement(statement, types, diagnostics);
    }

    private void visitStatement(Statement statement, Map<String, String> types, List<Diagnostic> diagnostics) {
        if (statement instanceof BlockStatement block) visitBlock(block, types, diagnostics);
        else if (statement instanceof VariableDeclaration variable) variable.initializer().ifPresent(value -> visitExpression(value, false, types, diagnostics));
        else if (statement instanceof ArrayDeclaration array) visitExpression(array.size(), false, types, diagnostics);
        else if (statement instanceof ReturnStatement returned) returned.value().ifPresent(value -> visitExpression(value, false, types, diagnostics));
        else if (statement instanceof ExpressionStatement expression) visitExpression(expression.expression(), true, types, diagnostics);
        else if (statement instanceof IfStatement conditional) {
            visitExpression(conditional.condition(), false, types, diagnostics); visitStatement(conditional.thenBranch(), types, diagnostics);
            conditional.elseBranch().ifPresent(branch -> visitStatement(branch, types, diagnostics));
        } else if (statement instanceof WhileStatement loop) {
            visitExpression(loop.condition(), false, types, diagnostics); visitStatement(loop.body(), types, diagnostics);
        } else if (statement instanceof DoWhileStatement loop) {
            visitStatement(loop.body(), types, diagnostics); visitExpression(loop.condition(), false, types, diagnostics);
        } else if (statement instanceof ForStatement loop) {
            loop.initializer().ifPresent(initializer -> visitStatement(initializer, types, diagnostics));
            loop.condition().ifPresent(condition -> visitExpression(condition, false, types, diagnostics));
            loop.update().ifPresent(update -> visitExpression(update, false, types, diagnostics));
            visitStatement(loop.body(), types, diagnostics);
        }
    }

    private void visitExpression(Expression expression, boolean standalone, Map<String, String> types, List<Diagnostic> diagnostics) {
        if (expression instanceof CallExpression call) {
            if (call.callee().equals("scanf")) validateScanf(call, standalone, types, diagnostics);
            call.arguments().forEach(argument -> visitExpression(argument, false, types, diagnostics));
        } else if (expression instanceof BinaryExpression binary) {
            visitExpression(binary.left(), false, types, diagnostics); visitExpression(binary.right(), false, types, diagnostics);
        } else if (expression instanceof UnaryExpression unary) visitExpression(unary.operand(), false, types, diagnostics);
        else if (expression instanceof AssignmentExpression assignment) {
            visitExpression(assignment.target(), false, types, diagnostics); visitExpression(assignment.value(), false, types, diagnostics);
        } else if (expression instanceof ArrayAccessExpression access) {
            visitExpression(access.array(), false, types, diagnostics); visitExpression(access.index(), false, types, diagnostics);
        }
    }

    private void validateCin(CallExpression call, boolean standalone, Map<String, String> types, List<Diagnostic> diagnostics) {
        if (!standalone || call.arguments().isEmpty()) {
            diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-INPUT-CIN-UNSUPPORTED",
                    "C++ std::cin extraction is supported only as a standalone chain into local scalar variables.", call.sourceSpan()));
            return;
        }
        for (Expression argument : call.arguments()) {
            if (!(argument instanceof NameExpression name)) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-INPUT-CIN-UNSUPPORTED",
                        "C++ std::cin extraction currently supports named scalar variables only.", argument.sourceSpan()));
                continue;
            }
            String type = types.getOrDefault(name.name(), "unknown");
            if (!Set.of("int", "float", "double", "char", "string").contains(type)) {
                diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-INPUT-CIN-TYPE",
                        "C++ std::cin extraction requires a declared int, float, double, char, or string variable.", name.sourceSpan()));
            }
        }
    }
    private void validateScanf(CallExpression call, boolean standalone, Map<String, String> types, List<Diagnostic> diagnostics) {
        if (!standalone || call.arguments().size() != 2 || !(call.arguments().getFirst() instanceof LiteralExpression format)
                || !(format.value() instanceof String spelling) || !(call.arguments().get(1) instanceof NameExpression destination)) {
            unsupported(call, diagnostics); return;
        }
        String expected = switch (spelling) { case "%d" -> "int"; case "%f" -> "float"; case "%lf" -> "double"; case "%c" -> "char"; default -> null; };
        if (expected == null) { unsupported(call, diagnostics); return; }
        String actual = types.getOrDefault(destination.name(), "unknown");
        if (!actual.equals(expected)) diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-INPUT-SCANF-TYPE",
                "Format '" + spelling + "' requires a local variable of type '" + expected + "'.", destination.sourceSpan()));
    }

    private void unsupported(CallExpression call, List<Diagnostic> diagnostics) {
        diagnostics.add(Diagnostic.at(Severity.ERROR, "CB-INPUT-SCANF-UNSUPPORTED",
                "Only standalone scanf(\"%d\", &intVar), scanf(\"%f\", &floatVar), and scanf(\"%lf\", &doubleVar) and scanf(\"%c\", &charVar) calls are supported for Java conversion.", call.sourceSpan()));
    }

    private String normalize(String type) { if (type.endsWith("[]")) return normalize(type.substring(0, type.length() - 2)) + "[]"; return switch (type) { case "_Bool", "boolean" -> "bool"; case "c-long" -> "long"; default -> type; }; }
}