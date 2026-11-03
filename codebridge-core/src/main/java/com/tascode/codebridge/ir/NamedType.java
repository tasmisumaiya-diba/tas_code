package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A declared class, interface, enum, aggregate, alias, or language library type. */
public record NamedType(int irVersion, SourceSpan sourceSpan, String qualifiedName, Kind kind,
                        Optional<String> declarationId, List<IrType> typeArguments) implements IrType {
    public enum Kind { CLASS, INTERFACE, ENUM, STRUCT, UNION, TYPE_ALIAS, LIBRARY, OTHER }
    public NamedType {
        IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(qualifiedName);
        Objects.requireNonNull(kind); declarationId = declarationId == null ? Optional.empty() : declarationId;
        typeArguments = List.copyOf(Objects.requireNonNull(typeArguments));
        if (qualifiedName.isBlank()) throw new IllegalArgumentException("Qualified type name is required");
        declarationId.ifPresent(id -> { if (id.isBlank()) throw new IllegalArgumentException("Declaration ID cannot be blank"); });
        typeArguments.forEach(type -> IrVersions.requireMatching(irVersion, type));
    }
}
