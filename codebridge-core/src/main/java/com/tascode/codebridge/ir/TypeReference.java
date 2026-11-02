package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Recursive language-neutral type shape, including generic arguments and array dimensions. */
public record TypeReference(int irVersion, SourceSpan sourceSpan, String name,
                            List<TypeReference> typeArguments, int arrayDimensions,
                            BoundKind boundKind, Optional<TypeReference> bound)
        implements IrNode {
    public enum BoundKind { NONE, EXTENDS, SUPER }

    public TypeReference {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan);
        Objects.requireNonNull(name);
        typeArguments = List.copyOf(Objects.requireNonNull(typeArguments));
        Objects.requireNonNull(boundKind);
        bound = bound == null ? Optional.empty() : bound;
        if (name.isBlank() || arrayDimensions < 0) throw new IllegalArgumentException("Type name and dimensions are invalid");
        typeArguments.forEach(argument -> IrVersions.requireMatching(irVersion, argument));
        bound.ifPresent(value -> IrVersions.requireMatching(irVersion, value));
        if ((boundKind == BoundKind.NONE) != bound.isEmpty()) throw new IllegalArgumentException("A wildcard bound must match its bound kind");
        if (boundKind != BoundKind.NONE && !name.equals("?")) throw new IllegalArgumentException("Only wildcard types may have bounds");
    }

    public TypeReference(int irVersion, SourceSpan sourceSpan, String name) {
        this(irVersion, sourceSpan, name, List.of(), 0, BoundKind.NONE, Optional.empty());
    }
}
