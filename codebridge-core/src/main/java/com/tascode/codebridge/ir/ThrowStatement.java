package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Raises an exception value; the thrown expression is required. */
public record ThrowStatement(int irVersion, SourceSpan sourceSpan, Expression value) implements Statement {
    public ThrowStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(value, "value");
        IrVersions.requireMatching(irVersion, value);
    }
}