package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Prefix or postfix increment/decrement of a local name. */
public record UpdateExpression(int irVersion, SourceSpan sourceSpan, NameExpression target, String operator,
                               boolean prefix) implements Expression {
    public UpdateExpression {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operator, "operator");
        if (!operator.equals("++") && !operator.equals("--")) throw new IllegalArgumentException("Unsupported update operator");
        IrVersions.requireMatching(irVersion, target);
    }
}