package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/** Generic wildcard or bounded type argument. */
public record WildcardType(int irVersion, SourceSpan sourceSpan, BoundKind boundKind,
                           Optional<IrType> bound) implements IrType {
    public enum BoundKind { UNBOUNDED, EXTENDS, SUPER }
    public WildcardType {
        IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(boundKind);
        bound = bound == null ? Optional.empty() : bound;
        if ((boundKind == BoundKind.UNBOUNDED) != bound.isEmpty()) throw new IllegalArgumentException("Wildcard bound must match bound kind");
        bound.ifPresent(type -> IrVersions.requireMatching(irVersion, type));
    }
}
