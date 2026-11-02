package com.tascode.codebridge.rules.standard;

import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.rules.ConversionRule;
import com.tascode.codebridge.rules.RuleContext;
import java.util.*;

/** Rewrites scalar C/C++ conditions into explicit Java boolean comparisons. */
public final class NumericConditionRule implements ConversionRule {
    private static final Set<String> NUMERIC = Set.of("byte", "int", "long", "short", "char", "float", "double");
    private Map<String, FunctionDeclaration> functions;

    @Override public boolean appliesTo(Language from, Language to, IrNode node) {
        return node instanceof Program && from != Language.JAVA && to == Language.JAVA;
    }
    @Override public int priority() { return 50; }

    @Override public synchronized IrNode rewrite(IrNode node, RuleContext context) {
        Program program = (Program) node;
        functions = new HashMap<>();
        program.functions().forEach(function -> functions.put(function.name(), function));
        List<FunctionDeclaration> rewritten = new ArrayList<>();
        for (FunctionDeclaration function : program.functions()) {
            Map<String, String> variables = new HashMap<>();
            function.parameters().forEach(parameter -> variables.put(parameter.name(), normalize(parameter.type())));
            rewritten.add(new FunctionDeclaration(function.irVersion(), function.sourceSpan(), function.returnType(), function.name(),
                    function.parameters(), rewriteBlock(function.body(), variables)));
        }
        return new Program(program.irVersion(), program.sourceSpan(), rewritten, program.includes(), program.globals(), program.enums(), program.classes());
    }

    private BlockStatement rewriteBlock(BlockStatement block, Map<String, String> parent) {
        Map<String, String> variables = new HashMap<>(parent);
        List<Statement> statements = new ArrayList<>();
        for (Statement statement : block.statements()) statements.add(rewriteStatement(statement, variables));
        return new BlockStatement(block.irVersion(), block.sourceSpan(), statements);
    }

    private Statement rewriteStatement(Statement statement, Map<String, String> variables) {
        if (statement instanceof BlockStatement block) return rewriteBlock(block, variables);
        if (statement instanceof VariableDeclaration variable) {
            VariableDeclaration rewritten = new VariableDeclaration(variable.irVersion(), variable.sourceSpan(), variable.name(), variable.type(),
                    variable.initializer().map(value -> rewriteExpression(value, variables)));
            variables.put(variable.name(), normalize(variable.type())); return rewritten;
        }
        if (statement instanceof ArrayDeclaration array) {
            variables.put(array.name(), normalize(array.elementType()) + "[]");
            return new ArrayDeclaration(array.irVersion(), array.sourceSpan(), array.elementType(), array.name(), rewriteExpression(array.size(), variables));
        }
        if (statement instanceof ReturnStatement returned) return new ReturnStatement(returned.irVersion(), returned.sourceSpan(), returned.value().map(value -> rewriteExpression(value, variables)));
        if (statement instanceof ExpressionStatement expression) return new ExpressionStatement(expression.irVersion(), expression.sourceSpan(), rewriteExpression(expression.expression(), variables));
        if (statement instanceof IfStatement conditional) return new IfStatement(conditional.irVersion(), conditional.sourceSpan(), normalizeCondition(conditional.condition(), variables),
                rewriteStatement(conditional.thenBranch(), new HashMap<>(variables)), conditional.elseBranch().map(branch -> rewriteStatement(branch, new HashMap<>(variables))));
        if (statement instanceof WhileStatement loop) return new WhileStatement(loop.irVersion(), loop.sourceSpan(), normalizeCondition(loop.condition(), variables), rewriteStatement(loop.body(), new HashMap<>(variables)));
        if (statement instanceof DoWhileStatement loop) return new DoWhileStatement(loop.irVersion(), loop.sourceSpan(), rewriteStatement(loop.body(), new HashMap<>(variables)), normalizeCondition(loop.condition(), variables));
        if (statement instanceof ForStatement loop) {
            Map<String, String> loopVariables = new HashMap<>(variables);
            Optional<Statement> initializer = loop.initializer().map(value -> rewriteStatement(value, loopVariables));
            return new ForStatement(loop.irVersion(), loop.sourceSpan(), initializer, loop.condition().map(value -> normalizeCondition(value, loopVariables)),
                    loop.update().map(value -> rewriteExpression(value, loopVariables)), rewriteStatement(loop.body(), loopVariables));
        }
        return statement;
    }

    private Expression normalizeCondition(Expression condition, Map<String, String> variables) {
        Expression rewritten = rewriteExpression(condition, variables);
        return numeric(typeOf(rewritten, variables)) ? comparison(rewritten, "!=") : rewritten;
    }

    private Expression rewriteExpression(Expression expression, Map<String, String> variables) {
        if (expression instanceof BinaryExpression binary) {
            Expression left = rewriteExpression(binary.left(), variables), right = rewriteExpression(binary.right(), variables);
            if (binary.operator().equals("&&") || binary.operator().equals("||")) {
                if (numeric(typeOf(left, variables))) left = comparison(left, "!=");
                if (numeric(typeOf(right, variables))) right = comparison(right, "!=");
            }
            return new BinaryExpression(binary.irVersion(), binary.sourceSpan(), binary.operator(), left, right);
        }
        if (expression instanceof UnaryExpression unary) {
            Expression operand = rewriteExpression(unary.operand(), variables);
            if (unary.operator().equals("!") && numeric(typeOf(operand, variables))) return comparison(operand, "==");
            return new UnaryExpression(unary.irVersion(), unary.sourceSpan(), unary.operator(), operand);
        }
        if (expression instanceof CallExpression call) return new CallExpression(call.irVersion(), call.sourceSpan(), call.callee(), call.arguments().stream().map(arg -> rewriteExpression(arg, variables)).toList());
        if (expression instanceof ArrayAccessExpression access) return new ArrayAccessExpression(access.irVersion(), access.sourceSpan(), rewriteExpression(access.array(), variables), rewriteExpression(access.index(), variables));
        if (expression instanceof AssignmentExpression assignment) return new AssignmentExpression(assignment.irVersion(), assignment.sourceSpan(), rewriteExpression(assignment.target(), variables), rewriteExpression(assignment.value(), variables));
        if (expression instanceof UpdateExpression update) return new UpdateExpression(update.irVersion(), update.sourceSpan(), (NameExpression) rewriteExpression(update.target(), variables), update.operator(), update.prefix());
        if (expression instanceof BinaryExpression) return expression;
        return expression;
    }

    private BinaryExpression comparison(Expression expression, String operator) {
        LiteralExpression zero = new LiteralExpression(expression.irVersion(), expression.sourceSpan(), 0);
        return new BinaryExpression(expression.irVersion(), expression.sourceSpan(), operator, expression, zero);
    }

    private String typeOf(Expression expression, Map<String, String> variables) {
        if (expression instanceof LiteralExpression literal) {
            if (literal.value() instanceof Boolean) return "bool";
            if (literal.value() instanceof Character) return "char";
            if (literal.value() instanceof Long) return "long";
            if (literal.value() instanceof Float) return "float";
            if (literal.value() instanceof Double) return "double";
            if (literal.value() instanceof Number) return "int";
        } else if (expression instanceof NameExpression name) return variables.getOrDefault(name.name(), "unknown");
        else if (expression instanceof ArrayAccessExpression access) {
            String type = typeOf(access.array(), variables); return type.endsWith("[]") ? type.substring(0, type.length() - 2) : "unknown";
        } else if (expression instanceof UnaryExpression unary) return unary.operator().equals("!") ? "bool" : typeOf(unary.operand(), variables);
        else if (expression instanceof UpdateExpression update) return typeOf(update.target(), variables);
        else if (expression instanceof BinaryExpression binary) {
            if (Set.of("&&", "||", "==", "!=", "<", ">", "<=", ">=").contains(binary.operator())) return "bool";
            String left = typeOf(binary.left(), variables), right = typeOf(binary.right(), variables);
            if (numeric(left) && numeric(right)) return left;
        } else if (expression instanceof CallExpression call && functions.containsKey(call.callee())) return normalize(functions.get(call.callee()).returnType());
        else if (expression instanceof AssignmentExpression assignment) return typeOf(assignment.target(), variables);
        return "unknown";
    }

    private boolean numeric(String type) { return NUMERIC.contains(type); }
    private String normalize(String type) { if (type.endsWith("[]")) return normalize(type.substring(0, type.length() - 2)) + "[]"; return switch (type) { case "_Bool", "boolean" -> "bool"; case "c-long" -> "long"; default -> type; }; }
}
