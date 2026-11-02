package com.tascode.codebridge.frontend.clang;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Loss-minimizing semantic AST node exported by libclang. Attributes preserve additional compiler facts. */
public record SemanticAstNode(String kind, String name, String displayName, String type, String canonicalType,
                              String valueCategory, String referencedSymbolId, String referencedName,
                              Optional<SourceSpan> location, Map<String, String> attributes,
                              List<SemanticAstNode> children) {
    public SemanticAstNode {
        location = location == null ? Optional.empty() : location;
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        children = List.copyOf(children);
    }
}
