package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;

/** Multi-way branch on a selector value. Fallthrough is explicit per case so backends can preserve or reject it. */
public record SwitchStatement(int irVersion, SourceSpan sourceSpan, Expression selector, List<Case> cases) implements Statement {
    public SwitchStatement {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(selector, "selector");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        IrVersions.requireMatching(irVersion, selector);
        cases.forEach(item -> IrVersions.requireMatching(irVersion, item));
        if (cases.stream().filter(Case::isDefault).count() > 1)
            throw new IllegalArgumentException("A switch may declare at most one default case");
    }

    /**
     * One switch arm. A default arm has no labels; any other arm needs at least one.
     * {@code fallsThrough} is true for classic colon-style arms that continue into the next arm unless they end in a jump.
     */
    public record Case(int irVersion, SourceSpan sourceSpan, List<Expression> labels, boolean isDefault,
                       List<Statement> body, boolean fallsThrough) implements IrNode {
        public Case {
            IrVersions.requireSupported(irVersion);
            Objects.requireNonNull(sourceSpan, "sourceSpan");
            labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
            body = List.copyOf(Objects.requireNonNull(body, "body"));
            if (isDefault && !labels.isEmpty()) throw new IllegalArgumentException("A default case cannot have labels");
            if (!isDefault && labels.isEmpty()) throw new IllegalArgumentException("A case requires at least one label");
            labels.forEach(label -> IrVersions.requireMatching(irVersion, label));
            body.forEach(statement -> IrVersions.requireMatching(irVersion, statement));
        }
    }
}