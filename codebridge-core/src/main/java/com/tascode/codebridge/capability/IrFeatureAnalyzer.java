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
        } else if (node instanceof VariableDeclaration variable) {
            features.add("variables");
            variable.initializer().ifPresent(value -> visit(value, features));
        } else if (node instanceof ReturnStatement returned) {
            features.add("return");
            returned.value().ifPresent(value -> visit(value, features));
        } else if (node instanceof ExpressionStatement expressionStatement) {
            visit(expressionStatement.expression(), features);
        } else if (node instanceof BinaryExpression binary) {
            features.add("integer-arithmetic");
            visit(binary.left(), features);
            visit(binary.right(), features);
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
