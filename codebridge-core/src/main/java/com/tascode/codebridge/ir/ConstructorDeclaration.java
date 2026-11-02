package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Constructor body and checked exceptions are represented without flattening initialization order. */
public record ConstructorDeclaration(int irVersion, SourceSpan sourceSpan, String name,
                                    List<Parameter> parameters, List<TypeReference> thrownTypes,
                                    Set<String> modifiers, BlockStatement body) implements IrNode {
    public ConstructorDeclaration {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan);
        Objects.requireNonNull(name);
        parameters = List.copyOf(Objects.requireNonNull(parameters));
        thrownTypes = List.copyOf(Objects.requireNonNull(thrownTypes));
        modifiers = Set.copyOf(Objects.requireNonNull(modifiers));
        Objects.requireNonNull(body);
        if (name.isBlank()) throw new IllegalArgumentException("Constructor name is required");
        parameters.forEach(value -> IrVersions.requireMatching(irVersion, value));
        thrownTypes.forEach(value -> IrVersions.requireMatching(irVersion, value));
        IrVersions.requireMatching(irVersion, body);
    }
}
