package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Objects;
import java.util.Optional;

/** A translation-unit variable lowered from Java class state or a C/C++ global. */
public record GlobalVariableDeclaration(int irVersion, SourceSpan sourceSpan, String name,
                                       String type, Optional<Expression> initializer, boolean readOnly, String visibility)
        implements IrNode {
    public GlobalVariableDeclaration {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan);
        Objects.requireNonNull(name);
        Objects.requireNonNull(type);
        Objects.requireNonNull(visibility);
        initializer = initializer == null ? Optional.empty() : initializer;
        if (name.isBlank() || type.isBlank()) throw new IllegalArgumentException("Global variable name and type are required");
        initializer.ifPresent(value -> IrVersions.requireMatching(irVersion, value));
    }
}
