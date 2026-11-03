package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** A statement carrying a label that break/continue/goto may target. */
public record LabeledStatement(int irVersion, SourceSpan sourceSpan, String label, Statement body) implements Statement {
    public LabeledStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(body, "body");
        if (label.isBlank()) throw new IllegalArgumentException("Label name is required");
        IrVersions.requireMatching(irVersion, body);
    }
}