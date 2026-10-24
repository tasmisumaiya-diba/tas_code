package com.tascode.codebridge.preprocess;

import com.tascode.codebridge.diagnostic.Diagnostic;
import java.util.List;

public record PreprocessResult(String source, List<String> includes, List<Diagnostic> diagnostics) {
    public PreprocessResult { includes = List.copyOf(includes); diagnostics = List.copyOf(diagnostics); }
    public boolean successful() { return source != null && diagnostics.stream().noneMatch(d -> d.severity() == com.tascode.codebridge.diagnostic.Severity.ERROR); }
}
