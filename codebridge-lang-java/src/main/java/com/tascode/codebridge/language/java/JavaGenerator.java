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
        return switch (type) { case "bool", "_Bool" -> "boolean"; default -> type; };
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
        else if (statement instanceof VariableDeclaration variable) {
            out.append(javaType(variable.type())).append(' ').append(variable.name());
            variable.initializer().ifPresent(value -> { out.append(" = "); expression(out, value); });
            out.append(';');
        } else if (statement instanceof ExpressionStatement expressionStatement) {
            expression(out, expressionStatement.expression()); out.append(';');
        } else if (statement instanceof ReturnStatement returned) {
            out.append("return"); returned.value().ifPresent(value -> { out.append(' '); expression(out, value); }); out.append(';');
        } else throw new UnsupportedOperationException("Unsupported Java statement node: " + statement.getClass().getSimpleName());
    }

    private void expression(StringBuilder out, Expression expression) {
        if (expression instanceof LiteralExpression literal) {
            if (literal.value() instanceof String value) out.append('"').append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            else if (literal.value() instanceof Boolean value) out.append(value);
            else out.append(literal.value());
        } else if (expression instanceof NameExpression name) out.append(name.name());
        else if (expression instanceof BinaryExpression binary) {
            out.append('('); expression(out, binary.left()); out.append(' ').append(binary.operator()).append(' '); expression(out, binary.right()); out.append(')');
        } else if (expression instanceof CallExpression call) {
            out.append(call.callee()).append('(');
            for (int i = 0; i < call.arguments().size(); i++) {
                if (i > 0) out.append(", ");
                expression(out, call.arguments().get(i));
            }
            out.append(')');
        } else throw new UnsupportedOperationException("Unsupported Java expression node: " + expression.getClass().getSimpleName());
    }
}
