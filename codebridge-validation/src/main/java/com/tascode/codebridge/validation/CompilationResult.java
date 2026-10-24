package com.tascode.codebridge.validation;

import com.tascode.codebridge.diagnostic.Diagnostic;
import com.tascode.codebridge.language.Language;
import java.util.List;
import java.util.Objects;

/** Compiler outcome and stable diagnostics for one source unit. */
public record CompilationResult(Language language, ValidationStatus status, List<Diagnostic> diagnostics) {
    public CompilationResult {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(status, "status");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public boolean successful() {
        return status == ValidationStatus.VALID;
    }
}
