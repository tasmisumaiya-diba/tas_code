package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** A method signature and optional body; overload identity is preserved by its typed parameters. */
public record MethodDeclaration(int irVersion, SourceSpan sourceSpan, String name, TypeReference returnType,
                                List<Parameter> parameters, List<TypeParameter> typeParameters,
                                List<TypeReference> thrownTypes, Set<String> modifiers,
                                Optional<BlockStatement> body) implements IrNode {
    public MethodDeclaration {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan);
        Objects.requireNonNull(name);
        Objects.requireNonNull(returnType);
        parameters = List.copyOf(Objects.requireNonNull(parameters));
        typeParameters = List.copyOf(Objects.requireNonNull(typeParameters));
        thrownTypes = List.copyOf(Objects.requireNonNull(thrownTypes));
        modifiers = Set.copyOf(Objects.requireNonNull(modifiers));
        body = body == null ? Optional.empty() : body;
        if (name.isBlank()) throw new IllegalArgumentException("Method name is required");
        IrVersions.requireMatching(irVersion, returnType);
        parameters.forEach(value -> IrVersions.requireMatching(irVersion, value));
        typeParameters.forEach(value -> IrVersions.requireMatching(irVersion, value));
        thrownTypes.forEach(value -> IrVersions.requireMatching(irVersion, value));
        body.ifPresent(value -> IrVersions.requireMatching(irVersion, value));
        if (modifiers.contains("abstract") && body.isPresent()) throw new IllegalArgumentException("Abstract methods cannot have a body");
    }
}
