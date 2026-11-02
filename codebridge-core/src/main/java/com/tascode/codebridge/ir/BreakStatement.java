package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Break control transfer from the nearest enclosing loop. */
public record BreakStatement(int irVersion, SourceSpan sourceSpan) implements Statement {
    public BreakStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
    }
}