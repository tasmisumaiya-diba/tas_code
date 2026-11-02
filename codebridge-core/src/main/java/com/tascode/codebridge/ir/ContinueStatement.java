package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Continue control transfer from the nearest enclosing loop. */
public record ContinueStatement(int irVersion, SourceSpan sourceSpan) implements Statement {
    public ContinueStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
    }
}