package com.tascode.codebridge.capability;

import com.tascode.codebridge.ir.*;
import java.util.LinkedHashSet;
import java.util.Set;

/** Derives the declared target features required by an IR tree. */
public final class IrFeatureAnalyzer {
    public Set<String> requiredFeatures(IrNode root) {
        LinkedHashSet<String> features = new LinkedHashSet<>();
        visit(root, features);
        return Set.copyOf(features);
    }

    private void visit(IrNode node, Set<String> features) {
        if (node instanceof Program program) {
            for (FunctionDeclaration function : program.functions()) visit(function, features);
        } else if (node instanceof FunctionDeclaration function) {
            features.add("functions");
            for (Parameter parameter : function.parameters()) visit(parameter, features);
            visit(function.body(), features);
        } else if (node instanceof Parameter) {
            features.add("functions");
        } else if (node instanceof BlockStatement block) {
            features.add("blocks");
            for (Statement statement : block.statements()) visit(statement, features);
        } else if (node instanceof ArrayDeclaration array) {
            features.add("arrays");
            visit(array.size(), features);
        } else if (node instanceof VariableDeclaration variable) {
            features.add("variables");
            variable.initializer().ifPresent(value -> visit(value, features));
        } else if (node instanceof ReturnStatement returned) {
            features.add("return");
            returned.value().ifPresent(value -> visit(value, features));
        } else if (node instanceof ExpressionStatement expressionStatement) {
            visit(expressionStatement.expression(), features);
        } else if (node instanceof ForStatement loop) {
            features.add("loops");
            loop.initializer().ifPresent(initializer -> visit(initializer, features));
            loop.condition().ifPresent(condition -> visit(condition, features));
            loop.update().ifPresent(update -> visit(update, features));
            visit(loop.body(), features);
        } else if (node instanceof IfStatement conditional) {
            features.add("conditionals");
            visit(conditional.condition(), features);
            visit(conditional.thenBranch(), features);
            conditional.elseBranch().ifPresent(branch -> visit(branch, features));
        } else if (node instanceof DoWhileStatement loop) {
            features.add("loops");
            visit(loop.body(), features);
            visit(loop.condition(), features);
        } else if (node instanceof WhileStatement loop) {
            features.add("loops");
            visit(loop.condition(), features);
            visit(loop.body(), features);
        } else if (node instanceof BreakStatement || node instanceof ContinueStatement) {
            features.add("loop-control");
        } else if (node instanceof BinaryExpression binary) {
            if (binary.operator().equals("&&") || binary.operator().equals("||")) features.add("boolean-logic");
            else if (java.util.Set.of("==", "!=", "<", ">", "<=", ">=").contains(binary.operator())) features.add("comparisons");
            else features.add("integer-arithmetic");
            visit(binary.left(), features);
            visit(binary.right(), features);
        } else if (node instanceof ArrayAccessExpression access) {
            features.add("arrays");
            visit(access.array(), features);
            visit(access.index(), features);
        } else if (node instanceof AssignmentExpression assignment) {
            features.add("assignments");
            visit(assignment.target(), features);
            visit(assignment.value(), features);
        } else if (node instanceof UpdateExpression update) {
            features.add("assignments");
            visit(update.target(), features);
        } else if (node instanceof UnaryExpression unary) {
            features.add(unary.operator().equals("!") ? "boolean-logic" : "integer-arithmetic");
            visit(unary.operand(), features);
        } else if (node instanceof CallExpression call) {
            features.add("calls");
            for (Expression argument : call.arguments()) visit(argument, features);
        } else if (node instanceof LiteralExpression || node instanceof NameExpression) {
            // Literals and local names are covered by their containing construct.
        } else {
            features.add("ir-node:" + node.getClass().getSimpleName());
        }
    }
}
