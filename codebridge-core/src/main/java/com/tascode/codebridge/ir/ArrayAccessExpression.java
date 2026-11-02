package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Indexed read from a one-dimensional array. */
public record ArrayAccessExpression(int irVersion, SourceSpan sourceSpan, Expression array, Expression index)
        implements Expression {
    public ArrayAccessExpression {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(array, "array");
        Objects.requireNonNull(index, "index");
        if (!(array instanceof NameExpression || array instanceof ArrayAccessExpression)) {
            throw new IllegalArgumentException("Array access must start with a named array");
        }
        IrVersions.requireMatching(irVersion, array);
        IrVersions.requireMatching(irVersion, index);
    }
}