package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** A language-level reference/managed handle, distinct from a raw pointer and value type. */
public record ReferenceType(int irVersion, SourceSpan sourceSpan, IrType referentType, ReferenceKind kind,
                            Ownership ownership, Nullability nullability) implements IrType {
    public enum ReferenceKind { LVALUE, RVALUE, MANAGED_OBJECT, BORROWED_VIEW }
    public enum Ownership { UNSPECIFIED, BORROWED, OWNED, SHARED, WEAK, GARBAGE_COLLECTED }
    public enum Nullability { UNSPECIFIED, NON_NULL, NULLABLE }
    public ReferenceType {
        IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(referentType);
        Objects.requireNonNull(kind); Objects.requireNonNull(ownership); Objects.requireNonNull(nullability);
        IrVersions.requireMatching(irVersion, referentType);
        if ((kind == ReferenceKind.LVALUE || kind == ReferenceKind.RVALUE) && nullability == Nullability.NULLABLE)
            throw new IllegalArgumentException("C++ lvalue/rvalue references cannot be null");
        if (ownership == Ownership.WEAK && nullability == Nullability.NON_NULL)
            throw new IllegalArgumentException("A weak reference cannot guarantee a non-null referent");
    }
}
