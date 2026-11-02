package com.tascode.codebridge.ir;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Language-neutral class/interface/enum/record shape retaining member and inheritance metadata. */
public record ClassDeclaration(int irVersion, SourceSpan sourceSpan, String name, Kind kind,
                               Set<String> modifiers, String visibility,
                               List<TypeParameter> typeParameters, Optional<TypeReference> superclass,
                               List<TypeReference> interfaces, List<FieldDeclaration> fields,
                               List<ConstructorDeclaration> constructors, List<MethodDeclaration> methods,
                               List<ClassDeclaration> nestedTypes) implements IrNode {
    public enum Kind { CLASS, INTERFACE, ENUM, RECORD }

    public ClassDeclaration {
        IrVersions.requireSupported(irVersion);
        Objects.requireNonNull(sourceSpan);
        Objects.requireNonNull(name);
        Objects.requireNonNull(kind);
        modifiers = Set.copyOf(Objects.requireNonNull(modifiers));
        Objects.requireNonNull(visibility);
        typeParameters = List.copyOf(Objects.requireNonNull(typeParameters));
        superclass = superclass == null ? Optional.empty() : superclass;
        interfaces = List.copyOf(Objects.requireNonNull(interfaces));
        fields = List.copyOf(Objects.requireNonNull(fields));
        constructors = List.copyOf(Objects.requireNonNull(constructors));
        methods = List.copyOf(Objects.requireNonNull(methods));
        nestedTypes = List.copyOf(Objects.requireNonNull(nestedTypes));
        if (name.isBlank()) throw new IllegalArgumentException("Type name is required");
        superclass.ifPresent(value -> IrVersions.requireMatching(irVersion, value));
        typeParameters.forEach(value -> IrVersions.requireMatching(irVersion, value));
        interfaces.forEach(value -> IrVersions.requireMatching(irVersion, value));
        fields.forEach(value -> IrVersions.requireMatching(irVersion, value));
        constructors.forEach(value -> IrVersions.requireMatching(irVersion, value));
        methods.forEach(value -> IrVersions.requireMatching(irVersion, value));
        nestedTypes.forEach(value -> IrVersions.requireMatching(irVersion, value));
    }
}
