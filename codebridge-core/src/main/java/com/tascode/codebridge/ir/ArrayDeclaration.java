package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;

/** Declaration of a one-dimensional array with an explicit size. */
public record ArrayDeclaration(int irVersion, SourceSpan sourceSpan, String elementType, String name, Expression size)
        implements Statement {
    public ArrayDeclaration {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan, "sourceSpan");
        Objects.requireNonNull(elementType, "elementType");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(size, "size");
        if (elementType.isBlank() || name.isBlank()) throw new IllegalArgumentException("Array type and name are required");
        IrVersions.requireMatching(irVersion, size);
    }
}