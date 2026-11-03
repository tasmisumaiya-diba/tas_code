package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Produces the value of an enclosing switch expression arm. */
public record YieldStatement(int irVersion, SourceSpan sourceSpan, Expression value) implements Statement {
    public YieldStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(value, "value");
        IrVersions.requireMatching(irVersion, value);
    }
}