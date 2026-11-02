package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Assignment to a local variable or array element. */
public record AssignmentExpression(int irVersion, SourceSpan sourceSpan, Expression target, Expression value)
        implements Expression {
    public AssignmentExpression {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(value, "value");
        if (!(target instanceof NameExpression || target instanceof ArrayAccessExpression)) {
            throw new IllegalArgumentException("Assignment target must be a variable or array element");
        }
        IrVersions.requireMatching(irVersion, target);
        IrVersions.requireMatching(irVersion, value);
    }
}