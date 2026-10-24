package com.tascode.codebridge.language.java;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.diagnostic.Severity;
import com.tascode.codebridge.ir.*;
import com.tascode.codebridge.language.Language;
import com.tascode.codebridge.language.LanguageGenerator;
import java.util.List;

/** Generates Java classes containing the supported static-method IR subset. */
public final class JavaGenerator implements LanguageGenerator {
    @Override public Language language() { return Language.JAVA; }

    @Override public GenerationResult generate(IrNode root) {
        if (!(root instanceof Program program)) {
            return new GenerationResult(null, List.of(Diagnostic.withoutLocation(Severity.ERROR, "CB-GEN-JAVA-ROOT", "Java generation requires a program node.")));
        }
        StringBuilder out = new StringBuilder("public class TasCodeGenerated {\n");
        if (containsIntegerInput(program)) out.append("    private static final java.util.Scanner CODEBRIDGE_INPUT = new java.util.Scanner(System.in).useLocale(java.util.Locale.ROOT);\n");
        if (containsCharInput(program)) out.append("    private static char codeBridgeReadCharOr(char previous) { String next = CODEBRIDGE_INPUT.findWithinHorizon(\"(?s).\", 0); return next == null ? previous : next.charAt(0); }\n");
        try {
            for (FunctionDeclaration function : program.functions()) {
                out.append("    public static ").append(javaType(function.returnType())).append(' ').append(function.name()).append('(');
                for (int i = 0; i < function.parameters().size(); i++) {
                    if (i > 0) out.append(", ");
                    Parameter parameter = function.parameters().get(i);
                    out.append(javaType(parameter.type())).append(' ').append(parameter.name());
                }
                out.append(") ");
                block(out, function.body(), 1);
                out.append('\n');
            }
            out.append("}\n");
            return new GenerationResult(out.toString(), List.of());
        } catch (UnsupportedOperationException exception) {
            return new GenerationResult(null, List.of(Diagnostic.withoutLocation(Severity.ERROR, "CB-GEN-JAVA-UNSUPPORTED", exception.getMessage())));
        }
    }

    private String javaType(String type) {
        return switch (type) { case "bool", "_Bool" -> "boolean"; case "string", "String" -> "String"; default -> type; };
    }

    private void block(StringBuilder out, BlockStatement block, int depth) {
        out.append("{\n");
        for (Statement statement : block.statements()) {
            out.append("    ".repeat(depth + 1));
            statement(out, statement, depth + 1);
            out.append('\n');
        }
        out.append("    ".repeat(depth)).append('}');
    }

    private void statement(StringBuilder out, Statement statement, int depth) {
        if (statement instanceof BlockStatement block) block(out, block, depth);
        else if (statement instanceof ArrayDeclaration array) {
            out.append(javaType(array.elementType())).append("[] ").append(array.name()).append(" = new ")
                    .append(javaType(array.elementType())).append('[');
            expression(out, array.size()); out.append("];");
        } else if (statement instanceof VariableDeclaration variable) {
            out.append(javaType(variable.type())).append(' ').append(variable.name());
            if (variable.initializer().isPresent()) {
                out.append(" = "); expression(out, variable.initializer().orElseThrow());
            } else {
                String defaultValue = switch (variable.type()) {
                    case "int", "long", "short", "byte" -> "0";
                    case "float" -> "0.0f";
                    case "double" -> "0.0d";
                    case "char" -> "'\\0'";
                    case "bool", "_Bool", "boolean" -> "false";
                    default -> null;
                };
                if (defaultValue != null) out.append(" = ").append(defaultValue);
            }
            out.append(';');
        } else if (statement instanceof ExpressionStatement expressionStatement) {
            statementExpression(out, expressionStatement.expression()); out.append(';');
        } else if (statement instanceof ReturnStatement returned) {
            out.append("return"); returned.value().ifPresent(value -> { out.append(' '); expression(out, value); }); out.append(';');
        } else if (statement instanceof BreakStatement) {
            out.append("break;");
        } else if (statement instanceof ContinueStatement) {
            out.append("continue;");
        } else if (statement instanceof ForStatement loop) {
            out.append("for (");
            loop.initializer().ifPresent(initializer -> {
                if (initializer instanceof VariableDeclaration variable) {
                    out.append(javaType(variable.type())).append(' ').append(variable.name());
                    variable.initializer().ifPresent(value -> { out.append(" = "); expression(out, value); });
                } else if (initializer instanceof ExpressionStatement expressionStatement) {
                    expression(out, expressionStatement.expression());
                } else throw new UnsupportedOperationException("Unsupported Java for initializer");
            });
            out.append("; "); loop.condition().ifPresent(value -> expression(out, value));
            out.append("; "); loop.update().ifPresent(value -> statementExpression(out, value));
            out.append(") "); statement(out, loop.body(), depth);
        } else if (statement instanceof IfStatement conditional) {
            out.append("if ("); expression(out, conditional.condition()); out.append(") ");
            statement(out, conditional.thenBranch(), depth);
            conditional.elseBranch().ifPresent(branch -> { out.append(" else "); statement(out, branch, depth); });
        } else if (statement instanceof DoWhileStatement loop) {
            out.append("do "); statement(out, loop.body(), depth);
            out.append(" while ("); expression(out, loop.condition()); out.append(");");
        } else if (statement instanceof WhileStatement loop) {
            out.append("while ("); expression(out, loop.condition()); out.append(") ");
            statement(out, loop.body(), depth);
        } else throw new UnsupportedOperationException("Unsupported Java statement node: " + statement.getClass().getSimpleName());
    }

    private void statementExpression(StringBuilder out, Expression expression) {
        if (expression instanceof AssignmentExpression assignment) {
            expression(out, assignment.target()); out.append(" = "); expression(out, assignment.value());
        } else if (expression instanceof UpdateExpression update) {
            if (update.prefix()) out.append(update.operator());
            out.append(update.target().name());
            if (!update.prefix()) out.append(update.operator());
        } else expression(out, expression);
    }
    private String characterLiteral(char value) {
        return switch (value) {
            case '\n' -> "'\\n'"; case '\r' -> "'\\r'"; case '\t' -> "'\\t'";
            case '\b' -> "'\\b'"; case '\f' -> "'\\f'"; case '\\' -> "'\\\\'";
            case '\'' -> "'\\''"; case '"' -> "'\"'";
            default -> "'" + value + "'";
        };
    }
    private String quoteString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case 92 -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }
    private boolean containsCharInput(Program program) {
        return program.functions().stream().anyMatch(function -> containsCharInput(function.body()));
    }
    private boolean containsCharInput(Statement statement) {
        if (statement instanceof BlockStatement block) return block.statements().stream().anyMatch(this::containsCharInput);
        if (statement instanceof ExpressionStatement expression) return containsCharInput(expression.expression());
        if (statement instanceof IfStatement conditional) return containsCharInput(conditional.thenBranch()) || conditional.elseBranch().map(this::containsCharInput).orElse(false);
        if (statement instanceof WhileStatement loop) return containsCharInput(loop.body());
        if (statement instanceof DoWhileStatement loop) return containsCharInput(loop.body());
        if (statement instanceof ForStatement loop) return loop.initializer().map(this::containsCharInput).orElse(false) || containsCharInput(loop.body());
        return false;
    }
    private boolean containsCharInput(Expression expression) {
        if (expression instanceof CallExpression call) return call.callee().equals("__codebridge_read_char") || call.arguments().stream().anyMatch(this::containsCharInput);
        if (expression instanceof AssignmentExpression assignment) return containsCharInput(assignment.target()) || containsCharInput(assignment.value());
        return false;
    }
    private boolean containsIntegerInput(Program program) {
        return program.functions().stream().anyMatch(function -> containsIntegerInput(function.body()));
    }
    private boolean containsIntegerInput(Statement statement) {
        if (statement instanceof BlockStatement block) return block.statements().stream().anyMatch(this::containsIntegerInput);
        if (statement instanceof VariableDeclaration variable) return variable.initializer().map(this::containsIntegerInput).orElse(false);
        if (statement instanceof ExpressionStatement expression) return containsIntegerInput(expression.expression());
        if (statement instanceof ReturnStatement returned) return returned.value().map(this::containsIntegerInput).orElse(false);
        if (statement instanceof IfStatement conditional) return conditional.elseBranch().map(this::containsIntegerInput).orElse(false)
                || containsIntegerInput(conditional.thenBranch());
        if (statement instanceof WhileStatement loop) return containsIntegerInput(loop.body());
        if (statement instanceof DoWhileStatement loop) return containsIntegerInput(loop.body());
        if (statement instanceof ForStatement loop) return loop.initializer().map(this::containsIntegerInput).orElse(false) || containsIntegerInput(loop.body());
        return false;
    }
    private boolean containsIntegerInput(Expression expression) {
        if (expression instanceof CallExpression call) return call.callee().startsWith("__codebridge_read_") || call.arguments().stream().anyMatch(this::containsIntegerInput);
        if (expression instanceof AssignmentExpression assignment) return containsIntegerInput(assignment.target()) || containsIntegerInput(assignment.value());
        return false;
    }
    private void expression(StringBuilder out, Expression expression) {
        if (expression instanceof LiteralExpression literal) {
            if (literal.value() instanceof String value) out.append(quoteString(value));
            else if (literal.value() instanceof Boolean value) out.append(value);
            else if (literal.value() instanceof Character value) out.append(characterLiteral(value));
            else out.append(literal.value());
        } else if (expression instanceof NameExpression name) out.append(name.name());
        else if (expression instanceof BinaryExpression binary) {
            out.append('('); expression(out, binary.left()); out.append(' ').append(binary.operator()).append(' '); expression(out, binary.right()); out.append(')');
        } else if (expression instanceof ArrayAccessExpression access) {
            expression(out, access.array()); out.append('['); expression(out, access.index()); out.append(']');
        } else if (expression instanceof AssignmentExpression assignment) {
            out.append('('); expression(out, assignment.target()); out.append(" = ");
            expression(out, assignment.value()); out.append(')');
        } else if (expression instanceof UpdateExpression update) {
            out.append('(');
            if (update.prefix()) out.append(update.operator());
            out.append(update.target().name());
            if (!update.prefix()) out.append(update.operator());
            out.append(')');
        } else if (expression instanceof UnaryExpression unary) {
            out.append('(').append(unary.operator()); expression(out, unary.operand()); out.append(')');
        } else if (expression instanceof CallExpression call) {
            if (call.callee().startsWith("__codebridge_read_")) {
                if (call.callee().equals("__codebridge_read_char")) {
                    out.append("codeBridgeReadCharOr("); expression(out, call.arguments().getFirst()); out.append(')'); return;
                }
                String suffix = switch (call.callee()) { case "__codebridge_read_float" -> "Float"; case "__codebridge_read_double" -> "Double"; default -> "Int"; };
                out.append("(CODEBRIDGE_INPUT.hasNext").append(suffix).append("() ? CODEBRIDGE_INPUT.next").append(suffix).append("() : ");
                expression(out, call.arguments().getFirst()); out.append(')'); return;
            }
            out.append(call.callee()).append('(');
            for (int i = 0; i < call.arguments().size(); i++) {
                if (i > 0) out.append(", ");
                expression(out, call.arguments().get(i));
            }
            out.append(')');
        } else throw new UnsupportedOperationException("Unsupported Java expression node: " + expression.getClass().getSimpleName());
    }
}
