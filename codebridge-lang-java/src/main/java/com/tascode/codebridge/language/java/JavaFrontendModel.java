package com.tascode.codebridge.language.java;

import com.tascode.codebridge.diagnostic.SourceSpan;
import com.tascode.codebridge.ir.TypeParameter;
import com.tascode.codebridge.ir.TypeReference;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Immutable Java declaration index paired with shared, recursive IR type references. */
public record JavaFrontendModel(
        int languageLevel,
        Optional<String> packageName,
        List<String> imports,
        List<TypeModel> types,
        Set<String> syntaxKinds,
        List<ResolvedSymbol> resolvedSymbols,
        List<AttributedType> attributedTypes) {
    public JavaFrontendModel {
        packageName = packageName == null ? Optional.empty() : packageName;
        imports = List.copyOf(imports);
        types = List.copyOf(types);
        syntaxKinds = Set.copyOf(syntaxKinds);
        resolvedSymbols = List.copyOf(resolvedSymbols);
        attributedTypes = List.copyOf(attributedTypes);
    }

    /** A javac-resolved declaration or name reference, with its owner and attributed type. */
    public record ResolvedSymbol(String elementKind, String name, String qualifiedName, String type, SourceSpan sourceSpan) { }

    /** The compiler-attributed type of a syntax tree node (including expressions). */
    public record AttributedType(String syntaxKind, String type, SourceSpan sourceSpan) { }

    public record TypeModel(
            String kind,
            String name,
            Set<String> modifiers,
            List<TypeParameter> typeParameters,
            Optional<TypeReference> superclass,
            List<TypeReference> interfaces,
            List<MemberModel> members,
            List<TypeModel> nestedTypes) {
        public TypeModel {
            modifiers = Set.copyOf(modifiers);
            typeParameters = List.copyOf(typeParameters);
            superclass = superclass == null ? Optional.empty() : superclass;
            interfaces = List.copyOf(interfaces);
            members = List.copyOf(members);
            nestedTypes = List.copyOf(nestedTypes);
        }
    }

    public record MemberModel(
            String kind,
            String name,
            Optional<TypeReference> type,
            Set<String> modifiers,
            List<TypeParameter> typeParameters,
            List<TypeReference> parameterTypes,
            List<TypeReference> thrownTypes,
            boolean hasBody,
            SourceSpan sourceSpan,
            Optional<String> initializerSource) {
        public MemberModel {
            type = type == null ? Optional.empty() : type;
            initializerSource = initializerSource == null ? Optional.empty() : initializerSource;
            modifiers = Set.copyOf(modifiers);
            typeParameters = List.copyOf(typeParameters);
            parameterTypes = List.copyOf(parameterTypes);
            thrownTypes = List.copyOf(thrownTypes);
        }
    }
}
