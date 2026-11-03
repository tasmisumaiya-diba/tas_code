package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/** A generic/template type variable identified by its declaring scope, not just its spelling. */
public record TypeVariableType(int irVersion, SourceSpan sourceSpan, String name,
                               Optional<String> declarationId) implements IrType {
    public TypeVariableType {
        IrVersions.requireSupported(irVersion); Objects.requireNonNull(sourceSpan); Objects.requireNonNull(name);
        declarationId = declarationId == null ? Optional.empty() : declarationId;
        if (name.isBlank()) throw new IllegalArgumentException("Type variable name is required");
        declarationId.ifPresent(id -> { if (id.isBlank()) throw new IllegalArgumentException("Declaration ID cannot be blank"); });
    }
}
