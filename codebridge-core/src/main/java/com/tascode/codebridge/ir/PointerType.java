package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Set;

/** A machine pointer. It is distinct from both a reference and a managed object reference. */
public record PointerType(int irVersion, SourceSpan sourceSpan, IrType pointeeType, Set<Qualifier> qualifiers,
                          Ownership ownership, Nullability nullability) implements IrType {
    public enum Qualifier { CONST, VOLATILE, RESTRICT }
    public enum Ownership { UNSPECIFIED, BORROWED, OWNED, SHARED, WEAK }
    public enum Nullability { UNSPECIFIED, NON_NULL, NULLABLE }
    public PointerType {
        IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(pointeeType);
        qualifiers = Set.copyOf(Objects.requireNonNull(qualifiers)); Objects.requireNonNull(ownership); Objects.requireNonNull(nullability);
        IrVersions.requireMatching(irVersion, pointeeType);
        if (ownership == Ownership.WEAK && nullability == Nullability.NON_NULL)
            throw new IllegalArgumentException("A weak pointer cannot guarantee a non-null pointee");
    }
}
