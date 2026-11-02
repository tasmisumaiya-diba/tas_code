package com.tascode.codebridge.frontend.clang;

import com.tascode.codebridge.language.Language;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Compiler-resolved translation unit. AST nodes retain compiler syntax kinds and type/reference data. */
public record ClangSemanticModel(Language language, String standard, String compilerVersion, String targetTriple, int pointerWidth, List<SemanticAstNode> declarations,
                                Map<String, SemanticSymbol> symbols) {
    public ClangSemanticModel {
        declarations = List.copyOf(declarations);
        symbols = Collections.unmodifiableMap(new LinkedHashMap<>(symbols));
    }
}
