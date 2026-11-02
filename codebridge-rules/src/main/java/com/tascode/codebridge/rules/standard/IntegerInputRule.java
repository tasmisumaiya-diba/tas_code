package com.tascode.codebridge.rules.standard;

import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.rules.ConversionRule;
import com.tascode.codebridge.rules.RuleContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lowers validated native console input into Java scanner reads. */
public final class IntegerInputRule implements ConversionRule {
    @Override public boolean appliesTo(Language from, Language to, IrNode node) {
        return node instanceof Program && from != Language.JAVA && to == Language.JAVA;
    }
    @Override public int priority() { return 75; }

    @Override public IrNode rewrite(IrNode node, RuleContext context) {
        Program program = (Program) node;
        List<FunctionDeclaration> functions = program.functions().stream().map(function -> new FunctionDeclaration(
                function.irVersion(), function.sourceSpan(), function.returnType(), function.name(), function.parameters(),
                (BlockStatement) rewriteStatement(function.body(), declaredTypes(function), context.from()))).toList();
        return new Program(program.irVersion(), program.sourceSpan(), functions, program.includes(), program.globals(), program.enums(), program.classes());
    }

    private Map<String, String> declaredTypes(FunctionDeclaration function) {
        Map<String, String> types = new HashMap<>();
        function.parameters().forEach(parameter -> types.put(parameter.name(), parameter.type()));
        collectTypes(function.body(), types);
        return types;
    }

    private void collectTypes(Statement statement, Map<String, String> types) {
        if (statement instanceof BlockStatement block) block.statements().forEach(child -> collectTypes(child, types));
        else if (statement instanceof VariableDeclaration variable) types.put(variable.name(), variable.type());
        else if (statement instanceof ArrayDeclaration array) types.put(array.name(), array.elementType() + "[]");
        else if (statement instanceof IfStatement conditional) {
            collectTypes(conditional.thenBranch(), types); conditional.elseBranch().ifPresent(child -> collectTypes(child, types));
        } else if (statement instanceof WhileStatement loop) collectTypes(loop.body(), types);
        else if (statement instanceof DoWhileStatement loop) collectTypes(loop.body(), types);
        else if (statement instanceof ForStatement loop) {
            loop.initializer().ifPresent(child -> collectTypes(child, types)); collectTypes(loop.body(), types);
        }
    }

    private Statement rewriteStatement(Statement statement, Map<String, String> types, Language source) {
        if (statement instanceof BlockStatement block) return new BlockStatement(block.irVersion(), block.sourceSpan(),
                block.statements().stream().map(child -> rewriteStatement(child, types, source)).toList());
        if (statement instanceof ExpressionStatement expression && expression.expression() instanceof CallExpression call
                && call.callee().equals("scanf") && call.arguments().size() == 2
                && call.arguments().get(1) instanceof NameExpression target) {
            String format = call.arguments().getFirst() instanceof LiteralExpression literal ? String.valueOf(literal.value()) : "";
            String readType = switch (format) { case "%d" -> "int"; case "%f" -> "float"; case "%lf" -> "double"; case "%c" -> "char"; default -> throw new IllegalStateException("Validated scanf format changed before rewrite"); };
            return assignmentRead(expression, target, readType, false);
        }
        if (statement instanceof ExpressionStatement expression && expression.expression() instanceof CallExpression call
                && call.callee().equals("__codebridge_cin")) {
            List<Statement> reads = new ArrayList<>();
            for (Expression argument : call.arguments()) {
                NameExpression target = (NameExpression) argument;
                String readType = types.get(target.name());
                if (readType == null || !List.of("int", "float", "double", "char", "string").contains(readType))
                    throw new IllegalStateException("Validated C++ input type changed before rewrite");
                reads.add(assignmentRead(expression, target, readType, source == Language.CPP));
            }
            return new BlockStatement(expression.irVersion(), expression.sourceSpan(), reads);
        }
        if (statement instanceof VariableDeclaration variable) return variable;
        if (statement instanceof ArrayDeclaration array) return array;
        if (statement instanceof ReturnStatement returned) return returned;
        if (statement instanceof IfStatement conditional) return new IfStatement(conditional.irVersion(), conditional.sourceSpan(), conditional.condition(),
                rewriteStatement(conditional.thenBranch(), types, source), conditional.elseBranch().map(child -> rewriteStatement(child, types, source)));
        if (statement instanceof WhileStatement loop) return new WhileStatement(loop.irVersion(), loop.sourceSpan(), loop.condition(), rewriteStatement(loop.body(), types, source));
        if (statement instanceof DoWhileStatement loop) return new DoWhileStatement(loop.irVersion(), loop.sourceSpan(), rewriteStatement(loop.body(), types, source), loop.condition());
        if (statement instanceof ForStatement loop) return new ForStatement(loop.irVersion(), loop.sourceSpan(), loop.initializer().map(child -> rewriteStatement(child, types, source)),
                loop.condition(), loop.update(), rewriteStatement(loop.body(), types, source));
        return statement;
    }

    private Statement assignmentRead(ExpressionStatement statement, NameExpression target, String readType, boolean cppInput) {
        String markerType = cppInput && readType.equals("char") ? "token_char" : readType;
        CallExpression read = new CallExpression(statement.irVersion(), statement.sourceSpan(), "__codebridge_read_" + markerType, List.of(target));
        AssignmentExpression assignment = new AssignmentExpression(statement.irVersion(), statement.sourceSpan(), target, read);
        return new ExpressionStatement(statement.irVersion(), statement.sourceSpan(), assignment);
    }
}
