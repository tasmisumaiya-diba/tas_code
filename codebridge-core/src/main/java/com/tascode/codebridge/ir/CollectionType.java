package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/** Collection behavior independent of a particular library class name. */
public record CollectionType(int irVersion, SourceSpan sourceSpan, Kind kind,
                             Optional<IrType> keyType, IrType elementType,
                             Ordering ordering, Mutability mutability) implements IrType {
    public enum Kind { SEQUENCE, SET, MAP, QUEUE, DEQUE, STACK, ITERABLE, OTHER }
    public enum Ordering { INSERTION, SORTED, UNORDERED, PRIORITY, UNSPECIFIED }
    public enum Mutability { MUTABLE, IMMUTABLE, READ_ONLY_VIEW, UNSPECIFIED }
    public CollectionType {
        IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(kind);
        keyType = keyType == null ? Optional.empty() : keyType; Objects.requireNonNull(elementType);
        Objects.requireNonNull(ordering); Objects.requireNonNull(mutability);
        keyType.ifPresent(type -> IrVersions.requireMatching(irVersion, type)); IrVersions.requireMatching(irVersion, elementType);
        if ((kind == Kind.MAP) != keyType.isPresent()) throw new IllegalArgumentException("Only map collections require a key type");
    }
}
