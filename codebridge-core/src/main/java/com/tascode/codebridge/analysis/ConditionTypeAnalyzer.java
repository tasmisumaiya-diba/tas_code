package com.tascode.codebridge.analysis;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import java.util.*;

/** Checks operator operand categories and target-language condition requirements. */
public final class ConditionTypeAnalyzer {
    private static final Set<String> NUMERIC = Set.of("byte", "int", "long", "short", "char", "float", "double");

    public List<Diagnostic> analyze(Program program, Language from, Language to) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, String> functionReturns = new HashMap<>();
        program.functions().forEach(function -> functionReturns.put(function.name(), normalize(function.returnType())));
        for (FunctionDeclaration function : program.functions()) {
            Map<String, String> variables = new HashMap<>();
            function.parameters().forEach(parameter -> variables.put(parameter.name(), normalize(parameter.type())));
            functionReturns.forEach((name, type) -> variables.put("@function:" + name, type));
            visitBlock(function.body(), variables, from, to, diagnostics);
        }
        return List.copyOf(diagnostics);
    }

    private void visitBlock(BlockStatement block, Map<String, String> variables, Language from, Language to, List<Diagnostic> diagnostics) {
        for (Statement statement : block.statements()) {
            if (statement instanceof VariableDeclaration variable) {
                variable.initializer().ifPresent(value -> visitExpression(value, variables, from, to, diagnostics));
                variables.put(variable.name(), normalize(variable.type()));
            } else if (statement instanceof ArrayDeclaration array) {
                visitExpression(array.size(), variables, from, to, diagnostics);
                variables.put(array.name(), normalize(array.elementType()) + "[]");
            } else if (statement instanceof BlockStatement nested) visitBlock(nested, new HashMap<>(variables), from, to, diagnostics);
            else if (statement instanceof ExpressionStatement expression) visitExpression(expression.expression(), variables, from, to, diagnostics);
            else if (statement instanceof ReturnStatement returned) returned.value().ifPresent(value -> visitExpression(value, variables, from, to, diagnostics));
            else if (statement instanceof IfStatement conditional) {
                visitCondition(conditional.condition(), variables, from, to, diagnostics);
                visitStatement(conditional.thenBranch(), variables, from, to, diagnostics);
                conditional.elseBranch().ifPresent(branch -> visitStatement(branch, variables, from, to, diagnostics));
            } else if (statement instanceof WhileStatement loop) {
                visitCondition(loop.condition(), variables, from, to, diagnostics); visitStatement(loop.body(), variables, from, to, diagnostics);
            } else if (statement instanceof DoWhileStatement loop) {
                visitStatement(loop.body(), variables, from, to, diagnostics); visitCondition(loop.condition(), variables, from, to, diagnostics);
            } else if (statement instanceof ForStatement loop) {
                Map<String, String> loopVars = new HashMap<>(variables);
                loop.initializer().ifPresent(initializer -> visitStatement(initializer, loopVars, from, to, diagnostics));
                loop.condition().ifPresent(condition -> visitCondition(condition, loopVars, from, to, diagnostics));
                loop.update().ifPresent(update -> visitExpression(update, loopVars, from, to, diagnostics));
                visitStatement(loop.body(), loopVars, from, to, diagnostics);
            }
        }
    }

    private void visitStatement(Statement statement, Map<String, String> variables, Language from, Language to, List<Diagnostic> diagnostics) {
        if (statement instanceof BlockStatement block) visitBlock(block, new HashMap<>(variables), from, to, diagnostics);
        else if (statement instanceof VariableDeclaration variable) {
            variable.initializer().ifPresent(value -> visitExpression(value, variables, from, to, diagnostics));
            variables.put(variable.name(), normalize(variable.type()));
        } else if (statement instanceof ArrayDeclaration array) {
            visitExpression(array.size(), variables, from, to, diagnostics); variables.put(array.name(), normalize(array.elementType()) + "[]");
        } else if (statement instanceof ExpressionStatement expression) visitExpression(expression.expression(), variables, from, to, diagnostics);
        else if (statement instanceof ReturnStatement returned) returned.value().ifPresent(value -> visitExpression(value, variables, from, to, diagnostics));
        else if (statement instanceof IfStatement conditional) {
            visitCondition(conditional.condition(), variables, from, to, diagnostics);
            visitStatement(conditional.thenBranch(), variables, from, to, diagnostics);
            conditional.elseBranch().ifPresent(branch -> visitStatement(branch, variables, from, to, diagnostics));
        } else if (statement instanceof WhileStatement loop) {
            visitCondition(loop.condition(), variables, from, to, diagnostics); visitStatement(loop.body(), variables, from, to, diagnostics);
        } else if (statement instanceof DoWhileStatement loop) {
            visitStatement(loop.body(), variables, from, to, diagnostics); visitCondition(loop.condition(), variables, from, to, diagnostics);
        }        else if (statement instanceof ForStatement loop) {
            Map<String, String> loopVariables = new HashMap<>(variables);
            loop.initializer().ifPresent(initializer -> visitStatement(initializer, loopVariables, from, to, diagnostics));
            loop.condition().ifPresent(condition -> visitCondition(condition, loopVariables, from, to, diagnostics));
            loop.update().ifPresent(update -> visitExpression(update, loopVariables, from, to, diagnostics));
            visitStatement(loop.body(), loopVariables, from, to, diagnostics);
        }
    }

    private void visitCondition(Expression condition, Map<String, String> variables, Language from, Language to, List<Diagnostic> diagnostics) {
        visitExpression(condition, variables, from, to, diagnostics);
        String type = typeOf(condition, variables);
        if (!type.equals("unknown") && !type.equals("bool") && !(NUMERIC.contains(type) && from != Language.JAVA)) {
            error("CB-TYPE-CONDITION", "A boolean condition is required for this conversion.", condition, diagnostics);
        }
    }

    private void visitExpression(Expression expression, Map<String, String> variables, Language from, Language to, List<Diagnostic> diagnostics) {
        if (expression instanceof BinaryExpression binary) {
            visitExpression(binary.left(), variables, from, to, diagnostics); visitExpression(binary.right(), variables, from, to, diagnostics);
            String left = typeOf(binary.left(), variables), right = typeOf(binary.right(), variables), op = binary.operator();
            if (left.equals("unknown") || right.equals("unknown")) return;
            boolean logical = op.equals("&&") || op.equals("||");
            boolean equality = op.equals("==") || op.equals("!=");
            boolean relational = Set.of("<", ">", "<=", ">=").contains(op);
            boolean bitwise = Set.of("&", "|", "^", "<<", ">>").contains(op);
            boolean booleanBitwise = from == Language.JAVA && to == Language.CPP
                    && Set.of("&", "|", "^").contains(op) && left.equals("bool") && right.equals("bool");
            boolean integralOperands = Set.of("byte", "int", "long", "short", "char").contains(left)
                    && Set.of("byte", "int", "long", "short", "char").contains(right);
            if (logical && !(left.equals("bool") && right.equals("bool"))
                    && !(from != Language.JAVA && scalar(left) && scalar(right))) {
                error("CB-TYPE-BOOLEAN-OPERANDS", "Logical operator '" + op + "' requires boolean operands.", binary, diagnostics);
            } else if (bitwise && !integralOperands && !booleanBitwise) {
                error("CB-TYPE-INTEGRAL-OPERANDS", "Bitwise and shift operators require integral operands.", binary, diagnostics);
            } else if (relational && !(NUMERIC.contains(left) && NUMERIC.contains(right))) {
                error("CB-TYPE-NUMERIC-OPERANDS", "Comparison '" + op + "' requires numeric operands.", binary, diagnostics);
            } else if (equality && left.equals("string") && right.equals("string") && from == Language.JAVA && to == Language.CPP) {
                error("CB-STRING-COMPARE-SEMANTICS", "Cross-language string equality cannot preserve Java reference comparison and C++ value comparison in this subset.", binary, diagnostics);
            } else if (equality && !left.equals(right) && !(NUMERIC.contains(left) && NUMERIC.contains(right))) {
                error("CB-TYPE-COMPARE", "Equality operands have incompatible types '" + left + "' and '" + right + "'.", binary, diagnostics);
            } else if (!logical && !equality && !relational && !bitwise && !(NUMERIC.contains(left) && NUMERIC.contains(right)) && !(op.equals("+") && left.equals("string") && right.equals("string"))) {
                error("CB-TYPE-NUMERIC-OPERANDS", "Arithmetic operator '" + op + "' requires numeric operands.", binary, diagnostics);
            } else if (op.equals("%") && (left.equals("float") || left.equals("double") || right.equals("float") || right.equals("double"))) {
                error("CB-TYPE-INTEGRAL-OPERANDS", "Remainder operator '%' requires integral operands.", binary, diagnostics);
            }
        } else if (expression instanceof UnaryExpression unary) {
            visitExpression(unary.operand(), variables, from, to, diagnostics);
            String operand = typeOf(unary.operand(), variables);
            if (unary.operator().equals("!") && !operand.equals("unknown") && !operand.equals("bool")
                    && !(from != Language.JAVA && scalar(operand))) {
                error("CB-TYPE-BOOLEAN-OPERANDS", "Logical negation requires a boolean operand.", unary, diagnostics);
            } else if (unary.operator().equals("~") && !operand.equals("unknown")
                    && !Set.of("byte", "int", "long", "short", "char").contains(operand)) {
                error("CB-TYPE-INTEGRAL-OPERANDS", "Bitwise complement requires an integral operand.", unary, diagnostics);
            } else if (!unary.operator().equals("!") && !operand.equals("unknown") && !NUMERIC.contains(operand)) {
                error("CB-TYPE-NUMERIC-OPERANDS", "Unary '" + unary.operator() + "' requires a numeric operand.", unary, diagnostics);
            }
        } else if (expression instanceof CallExpression call) call.arguments().forEach(argument -> visitExpression(argument, variables, from, to, diagnostics));
        else if (expression instanceof ArrayAccessExpression access) { visitExpression(access.array(), variables, from, to, diagnostics); visitExpression(access.index(), variables, from, to, diagnostics); }
        else if (expression instanceof UpdateExpression update) visitExpression(update.target(), variables, from, to, diagnostics);
        else if (expression instanceof AssignmentExpression assignment) {
            visitExpression(assignment.target(), variables, from, to, diagnostics); visitExpression(assignment.value(), variables, from, to, diagnostics);
        }
    }

    private String typeOf(Expression expression, Map<String, String> variables) {
        if (expression instanceof LiteralExpression literal) {
            if (literal.value() instanceof Boolean) return "bool";
            if (literal.value() instanceof Character) return "char";
            if (literal.value() instanceof Long) return "long";
            if (literal.value() instanceof Float) return "float";
            if (literal.value() instanceof Double) return "double";
            if (literal.value() instanceof Number) return "int";
            if (literal.value() instanceof String) return "string";
        } else if (expression instanceof NameExpression name) return variables.getOrDefault(name.name(), "unknown");
        else if (expression instanceof CallExpression call) {
            if (call.callee().equals("__codebridge_array_length")) return "int";
            if (call.callee().endsWith(".equals")) return "bool";
            return variables.getOrDefault("@function:" + call.callee(), "unknown");
        }
        else if (expression instanceof ArrayAccessExpression access) {
            String type = typeOf(access.array(), variables); return type.endsWith("[]") ? type.substring(0, type.length() - 2) : "unknown";
        } else if (expression instanceof AssignmentExpression assignment) return typeOf(assignment.target(), variables);
        else if (expression instanceof UpdateExpression update) return typeOf(update.target(), variables);
        else if (expression instanceof BinaryExpression binary) {
            if (Set.of("==", "!=", "<", ">", "<=", ">=", "&&", "||").contains(binary.operator())) return "bool";
            String left = typeOf(binary.left(), variables), right = typeOf(binary.right(), variables);
            if (Set.of("&", "|", "^").contains(binary.operator()) && left.equals("bool") && right.equals("bool")) return "bool";
            if (Set.of("<<", ">>").contains(binary.operator())) return Set.of("byte", "short", "char").contains(left) ? "int" : left;
            if (NUMERIC.contains(left) && NUMERIC.contains(right)) return "int";
            if (binary.operator().equals("+") && left.equals("string") && right.equals("string")) return "string";
        } else if (expression instanceof UnaryExpression unary) return unary.operator().equals("!") ? "bool" : typeOf(unary.operand(), variables);
        return "unknown";
    }

    private boolean scalar(String type) { return NUMERIC.contains(type) || type.equals("bool"); }
    private String normalize(String type) { if (type.endsWith("[]")) return normalize(type.substring(0, type.length() - 2)) + "[]"; return switch (type) { case "_Bool", "boolean" -> "bool"; case "c-long" -> "long"; case "String" -> "string"; default -> type; }; }
    private void error(String code, String message, IrNode node, List<Diagnostic> diagnostics) {
        diagnostics.add(Diagnostic.at(Severity.ERROR, code, message, node.sourceSpan()));
    }
}
