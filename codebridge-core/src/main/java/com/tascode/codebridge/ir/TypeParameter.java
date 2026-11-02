package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;

/** A generic type parameter and its declared upper bounds. */
public record TypeParameter(int irVersion, SourceSpan sourceSpan, String name,
                            List<TypeReference> bounds) implements IrNode {
    public TypeParameter {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan);
        Objects.requireNonNull(name);
        bounds = List.copyOf(Objects.requireNonNull(bounds));
        if (name.isBlank()) throw new IllegalArgumentException("Type parameter name is required");
        bounds.forEach(bound -> IrVersions.requireMatching(irVersion, bound));
    }
}
