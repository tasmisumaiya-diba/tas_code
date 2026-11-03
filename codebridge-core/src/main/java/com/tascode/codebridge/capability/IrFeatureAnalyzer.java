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
            if (!program.enums().isEmpty()) features.add("enums");
            if (!program.classes().isEmpty()) features.add("classes");
            for (GlobalVariableDeclaration global : program.globals()) visit(global, features);
            for (ClassDeclaration type : program.classes()) visit(type, features);
            for (FunctionDeclaration function : program.functions()) visit(function, features);
        } else if (node instanceof GlobalVariableDeclaration global) {
            features.add("variables");
            if (stringType(global.type())) features.add("strings");
            if (byteType(global.type())) features.add("byte");
            global.initializer().ifPresent(value -> visit(value, features));
        } else if (node instanceof ClassDeclaration type) {
            features.add("classes");
            if (!type.typeParameters().isEmpty()) features.add("generics");
            if (type.superclass().isPresent() || !type.interfaces().isEmpty()) features.add("inheritance");
            type.fields().forEach(field -> visit(field, features));
            type.constructors().forEach(constructor -> visit(constructor, features));
            type.methods().forEach(method -> visit(method, features));
            type.nestedTypes().forEach(nested -> visit(nested, features));
        } else if (node instanceof FieldDeclaration field) {
            features.add("fields");
            if (stringType(field.type().name())) features.add("strings");
            if (byteType(field.type().name())) features.add("byte");
            if (!field.type().typeArguments().isEmpty()) features.add("generics");
            field.initializer().ifPresent(value -> visit(value, features));
        } else if (node instanceof MethodDeclaration method) {
            features.add("methods");
            if (stringType(method.returnType().name())) features.add("strings");
            if (byteType(method.returnType().name())) features.add("byte");
            if (!method.typeParameters().isEmpty()) features.add("generics");
            method.parameters().forEach(parameter -> visit(parameter, features));
            method.body().ifPresent(body -> visit(body, features));
        } else if (node instanceof ConstructorDeclaration constructor) {
            features.add("constructors");
            constructor.parameters().forEach(parameter -> visit(parameter, features));
            visit(constructor.body(), features);
        } else if (node instanceof FunctionDeclaration function) {
            features.add("functions");
            if (stringType(function.returnType())) features.add("strings");
            if (byteType(function.returnType())) features.add("byte");
            for (Parameter parameter : function.parameters()) visit(parameter, features);
            visit(function.body(), features);
        } else if (node instanceof Parameter parameter) {
            features.add("functions");
            if (stringType(parameter.type())) features.add("strings");
            if (byteType(parameter.type())) features.add("byte");
        } else if (node instanceof BlockStatement block) {
            features.add("blocks");
            for (Statement statement : block.statements()) visit(statement, features);
        } else if (node instanceof ArrayDeclaration array) {
            features.add("arrays");
            if (stringType(array.elementType())) features.add("strings");
            if (byteType(array.elementType())) features.add("byte");
            visit(array.size(), features);
        } else if (node instanceof VariableDeclaration variable) {
            features.add("variables");
            if (stringType(variable.type())) features.add("strings");
            if (byteType(variable.type())) features.add("byte");
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
            else if (Set.of("==", "!=", "<", ">", "<=", ">=").contains(binary.operator())) features.add("comparisons");
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
            if (!isConsoleCall(call.callee())) {
                for (Expression argument : call.arguments()) visit(argument, features);
            }
        } else if (node instanceof LiteralExpression literal) {
            if (literal.value() instanceof String) features.add("strings");
        } else if (node instanceof NameExpression) {
            // Local names are covered by their declarations.
        } else {
            features.add("ir-node:" + node.getClass().getSimpleName());
        }
    }

    private boolean byteType(String type) { return type.equals("byte"); }

    private boolean stringType(String type) {
        return type.equals("String") || type.equals("string");
    }

    private boolean isConsoleCall(String callee) {
        return Set.of("printf", "puts", "scanf", "System.out.printf", "System.out.print",
                "System.out.println", "__codebridge_cout", "__codebridge_cin").contains(callee);
    }
}
