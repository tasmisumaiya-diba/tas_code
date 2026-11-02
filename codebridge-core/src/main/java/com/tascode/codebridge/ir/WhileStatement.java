package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Pre-test loop that evaluates its condition before each iteration. */
public record WhileStatement(int irVersion, SourceSpan sourceSpan, Expression condition, Statement body)
        implements Statement {
    public WhileStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(body, "body");
        IrVersions.requireMatching(irVersion, condition);
        IrVersions.requireMatching(irVersion, body);
    }
}