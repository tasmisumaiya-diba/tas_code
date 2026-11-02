package com.tascode.codebridge.frontend.clang;

import com.tascode.codebridge.diagnostic.Diagnostic;
import java.util.List;
import java.util.Optional;

/** Result of parsing and semantic analysis by Clang. */
public record ClangSemanticResult(Optional<ClangSemanticModel> model, List<Diagnostic> diagnostics) {
    public ClangSemanticResult {
        model = model == null ? Optional.empty() : model;
        diagnostics = List.copyOf(diagnostics);
    }
    public boolean successful() {
        return model.isPresent() && diagnostics.stream().noneMatch(d -> d.severity() == com.tascode.codebridge.diagnostic.Severity.ERROR);
    }
}
