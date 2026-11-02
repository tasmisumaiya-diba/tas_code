package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Unary arithmetic or logical operator. */
public record UnaryExpression(int irVersion, SourceSpan sourceSpan, String operator, Expression operand)
        implements Expression {
    public UnaryExpression {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operand, "operand");
        if (!operator.equals("!") && !operator.equals("-") && !operator.equals("+") && !operator.equals("~")) {
            throw new IllegalArgumentException("Unsupported unary operator: " + operator);
        }
        IrVersions.requireMatching(irVersion, operand);
    }
}