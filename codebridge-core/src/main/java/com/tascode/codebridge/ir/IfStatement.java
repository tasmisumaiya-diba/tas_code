package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/** Conditional branch with an optional alternative. */
public record IfStatement(int irVersion, SourceSpan sourceSpan, Expression condition,
                          Statement thenBranch, Optional<Statement> elseBranch) implements Statement {
    public IfStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(thenBranch, "thenBranch");
        elseBranch = Objects.requireNonNull(elseBranch, "elseBranch");
        IrVersions.requireMatching(irVersion, condition);
        IrVersions.requireMatching(irVersion, thenBranch);
        elseBranch.ifPresent(branch -> IrVersions.requireMatching(irVersion, branch));
    }
}