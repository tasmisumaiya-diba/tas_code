package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A class field with its source modifiers and optional initializer. */
public record FieldDeclaration(int irVersion, SourceSpan sourceSpan, String name, TypeReference type,
                               Set<String> modifiers, Optional<Expression> initializer) implements IrNode {
    public FieldDeclaration {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan);
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
        modifiers = Set.copyOf(Objects.requireNonNull(modifiers));
        initializer = initializer == null ? Optional.empty() : initializer;
        if (name.isBlank()) throw new IllegalArgumentException("Field name is required");
        IrVersions.requireMatching(irVersion, type);
        initializer.ifPresent(value -> IrVersions.requireMatching(irVersion, value));
    }
}
