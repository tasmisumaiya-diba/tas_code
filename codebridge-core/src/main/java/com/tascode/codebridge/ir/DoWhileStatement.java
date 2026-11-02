package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Post-test loop that executes its body at least once. */
public record DoWhileStatement(int irVersion, SourceSpan sourceSpan, Statement body, Expression condition)
        implements Statement {
    public DoWhileStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(condition, "condition");
        IrVersions.requireMatching(irVersion, body);
        IrVersions.requireMatching(irVersion, condition);
    }
}