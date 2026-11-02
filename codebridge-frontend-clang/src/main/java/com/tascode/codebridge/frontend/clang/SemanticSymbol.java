package com.tascode.codebridge.frontend.clang;

import com.tascode.codebridge.diagnostic.SourceSpan;
import java.util.Optional;

/** A compiler-resolved declaration, indexed by Clang USR when available. */
public record SemanticSymbol(String id, String kind, String name, String scope, String type,
                             String canonicalType, boolean definition, Optional<SourceSpan> location) {
    public SemanticSymbol { location = location == null ? Optional.empty() : location; }
}
